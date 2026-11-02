#include <wallet_mpc/engine.h>
#include <wallet_mpc/store.h>
#include <wallet_mpc/kms.h>
#include <wallet_mpc/monero.h>
#include <wallet_mpc/authorization.h>

#include <httplib.h>
#include <json.hpp>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <deque>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <stdexcept>
#include <thread>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

#include <openssl/pem.h>
#include <openssl/sha.h>

namespace {
using namespace coinbase;
using namespace wallet_mpc;
using Json = nlohmann::json;
using Clock = std::chrono::steady_clock;

void require(bool condition, const char* message) {
  if (!condition) throw std::invalid_argument(message);
}

std::string hex(mem_t bytes) {
  const char* digits = "0123456789abcdef";
  std::string result;
  result.reserve(bytes.size * 2);
  for (int i = 0; i < bytes.size; ++i) {
    result += digits[bytes[i] >> 4]; result += digits[bytes[i] & 15];
  }
  return result;
}

buf_t unhex(const std::string& text, size_t maximum = 1024 * 1024) {
  require(text.size() % 2 == 0 && text.size() / 2 <= maximum, "invalid hex payload size");
  auto digit = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    throw std::invalid_argument("hex payload must use lowercase hexadecimal");
  };
  buf_t result(static_cast<int>(text.size() / 2));
  for (int i = 0; i < result.size(); ++i) result[i] = digit(text[i * 2]) * 16 + digit(text[i * 2 + 1]);
  return result;
}

std::string hash(const std::string& text) {
  uint8_t digest[32];
  require(SHA256(reinterpret_cast<const uint8_t*>(text.data()), text.size(), digest), "hash failed");
  return hex(mem_t(digest, sizeof(digest)));
}

std::string fingerprint(const X509* certificate) {
  // Stable SPKI identity survives certificate renewal with the same key.
  require(certificate != nullptr, "certificate is required");
  const auto key = X509_get0_pubkey(certificate);
  const int size = key ? i2d_PUBKEY(key, nullptr) : 0;
  require(size > 0 && size <= 8192, "certificate public key encoding failed");
  buf_t encoded(size);
  auto pointer = encoded.data();
  require(i2d_PUBKEY(key, &pointer) == size, "certificate public key encoding failed");
  uint8_t digest[32];
  require(SHA256(encoded.data(), encoded.size(), digest), "certificate fingerprint failed");
  return hex(mem_t(digest, sizeof(digest)));
}

std::string authenticated_party(const httplib::Request& request) {
  require(request.ssl, "TLS client identity is required");
  std::unique_ptr<X509, decltype(&X509_free)> certificate(
      SSL_get1_peer_certificate(static_cast<const SSL*>(request.ssl)), X509_free);
  return fingerprint(certificate.get());
}

std::string uuid(const Json& object, const char* field) {
  const auto value = object.at(field).get<std::string>();
  require(value.size() == 36, "UUID field is required");
  for (size_t i = 0; i < value.size(); ++i) {
    const bool hyphen = i == 8 || i == 13 || i == 18 || i == 23;
    require(hyphen ? value[i] == '-' : (value[i] >= '0' && value[i] <= '9') ||
                                      (value[i] >= 'a' && value[i] <= 'f'), "invalid UUID field");
  }
  return value;
}

Algorithm algorithm(const std::string& value) {
  if (value == "ECDSA_SECP256K1") return Algorithm::EcdsaSecp256k1;
  if (value == "ED25519") return Algorithm::Ed25519;
  if (value == "SCHNORR_BIP340") return Algorithm::SchnorrBip340;
  throw std::invalid_argument("unsupported signing algorithm");
}

Json read_json_file(const std::string& path) {
  std::ifstream file(path);
  require(file.good(), "cannot read node configuration");
  return Json::parse(file);
}

StorageKey node_storage_key(const Json& config) {
#ifdef WALLET_MPC_TEST_STORAGE_KEY
  // Synthetic fixture input exists only in the non-installed test executable.
  if (config.contains("testStorageKeyHex")) {
    require(!config.contains("storageKey") && !config.contains("encryptionKeyFile"), "ambiguous test key configuration");
    auto bytes = unhex(config.at("testStorageKeyHex").get<std::string>(), 32);
    try {
      StorageKey key(bytes);
      bytes.secure_bzero();
      return key;
    } catch (...) { bytes.secure_bzero(); throw; }
  }
#endif
  std::unique_ptr<BIO, decltype(&BIO_free)> file(
      BIO_new_file(config.at("certificateFile").get<std::string>().c_str(), "r"), BIO_free);
  std::unique_ptr<X509, decltype(&X509_free)> certificate(
      file ? PEM_read_bio_X509(file.get(), nullptr, nullptr, nullptr) : nullptr, X509_free);
  return load_storage_key(config, fingerprint(certificate.get()));
}

struct Peer {
  std::string identity;
  std::string host;
  int port;
};

struct Session {
  Json descriptor;
  std::string digest;
  std::vector<std::string> online;
  int self;
  Clock::time_point deadline;
  std::mutex mutex;
  std::condition_variable ready;
  std::map<std::string, std::deque<buf_t>> incoming;
  std::map<std::string, std::vector<std::string>> received_hashes;
  size_t queued_bytes = 0;
  bool finished = false;
  bool recover_monero = false;
  bool recover_refresh = false;
};

class Node;
class PeerTransport final : public api::data_transport_i {
 public:
  PeerTransport(Node& node, std::shared_ptr<Session> session) : node_(node), session_(std::move(session)) {}
  error_t send(api::party_idx_t receiver, mem_t message) override;
  error_t receive(api::party_idx_t sender, buf_t& message) override;
  error_t receive_all(const std::vector<api::party_idx_t>& senders, std::vector<buf_t>& messages) override;
 private:
  Node& node_;
  std::shared_ptr<Session> session_;
  std::map<int, int> next_sequence_;
};

class Node {
 public:
  explicit Node(Json config)
      : config_(std::move(config)),
        store_(config_.at("storeDirectory"), node_storage_key(config_)),
        server_(config_.at("certificateFile").get<std::string>().c_str(),
                config_.at("privateKeyFile").get<std::string>().c_str(),
                config_.at("caFile").get<std::string>().c_str()) {
    require(server_.is_valid(), "cannot initialize authenticated TLS server");
    std::unique_ptr<BIO, decltype(&BIO_free)> file(
        BIO_new_file(config_.at("certificateFile").get<std::string>().c_str(), "r"), BIO_free);
    std::unique_ptr<X509, decltype(&X509_free)> certificate(
        file ? PEM_read_bio_X509(file.get(), nullptr, nullptr, nullptr) : nullptr, X509_free);
    identity_ = fingerprint(certificate.get());
    require(SSL_CTX_set_min_proto_version(static_cast<SSL_CTX*>(server_.tls_context()), TLS1_3_VERSION) == 1,
            "cannot enforce TLS 1.3");
    int i = 0;
    for (const auto& peer : config_.at("peers")) {
      require(i < 3, "exactly three registered peers are required");
      Peer parsed{peer.at("identity"), peer.at("host"), peer.at("port")};
      require(unhex(parsed.identity).size() == 32 && !parsed.host.empty() && parsed.port > 0 && parsed.port <= 65535,
              "invalid registered peer");
      require(peers_.emplace(parsed.identity, parsed).second, "duplicate registered peer");
      parties_[i++] = parsed.identity;
    }
    require(i == 3 && peers_.count(identity_), "local certificate must be one of three registered peers");
    authorization_keys_ = config_.at("authorizationPublicKeys").get<std::vector<std::string>>();
    require(!authorization_keys_.empty() && authorization_keys_.size() <= 3, "independent authorization trust keys required");
    for (const auto& key : authorization_keys_) require(unhex(key).size() == 32, "invalid independent authorization public key");
    timeout_seconds_ = config_.value("sessionTimeoutSeconds", 30);
    require(timeout_seconds_ >= 5 && timeout_seconds_ <= 120, "session timeout is outside limits");
    server_.set_payload_max_length(10 * 1024 * 1024);
    server_.new_task_queue = [] { return new httplib::ThreadPool(8, 8, 64); };
    server_.set_read_timeout(5);
    server_.set_write_timeout(5);
    server_.set_exception_handler([](const auto&, auto& response, std::exception_ptr) {
      response.status = 400;
      response.set_content("{\"error\":\"request rejected\"}", "application/json");
    });
    server_.Get("/health", [&](const auto&, auto& response) {
      reply(response, Json{{"status", "UP"}, {"partyId", identity_}});
    });
    server_.Post("/v1/sessions", [&](const auto& request, auto& response) {
      auto descriptor = Json::parse(request.body);
      const auto proof = descriptor.value("authorization", Json());
      descriptor.erase("authorization");
      require(descriptor.is_object() && descriptor.size() == 8, "native request fields differ");
      authorize(authenticated_party(request), descriptor, descriptor.at("operation"));
      reply(response, submit(descriptor, proof));
    });
    server_.Post("/v1/results", [&](const auto& request, auto& response) {
      const auto query = Json::parse(request.body);
      authorize(authenticated_party(request), query, "READ_RESULT");
      reply(response, result(query));
    });
    server_.Post("/v1/messages", [&](const auto& request, auto& response) {
      receive_message(authenticated_party(request), Json::parse(request.body));
      reply(response, Json{{"accepted", true}});
    });
    // Public readiness only; a coordinator cannot fetch a share or impersonate a holder.
    server_.Post("/v1/refresh-prepared", [&](const auto& request, auto& response) {
      const auto caller = authenticated_party(request);
      require(peers_.count(caller) && caller != identity_, "only registered holders may check refresh readiness");
      const auto descriptor = Json::parse(request.body);
      const auto prepared = prepared_refresh(descriptor);
      reply(response, prepared ? Json{{"prepared", true}, {"publicKeyHex", prepared->at("publicKeyHex")},
                                    {"intentHash", hash(descriptor.dump())}}
                               : Json{{"prepared", false}});
    });
    server_.Post("/v1/monero-recovery-material", [&](const auto& request, auto& response) {
      const auto caller = authenticated_party(request);
      const auto descriptor = Json::parse(request.body);
      require(peers_.count(caller) && caller != identity_ && descriptor.at("operation") == "SIGN"
          && descriptor.at("algorithm") == "MONERO_NATIVE_2_OF_3" && descriptor.at("epoch") == 1
          && descriptor.at("online").size() == 2 && descriptor.at("online").at(0) == caller
          && descriptor.at("online").at(1) == identity_, "only the original native proposer may fetch its co-signer's material");
      const auto material = monero_recovery_material(descriptor, identity_, store_);
      reply(response, Json{{"material", material ? *material : Json(nullptr)}});
    });
  }

  ~Node() { if (worker_.joinable()) worker_.join(); }
  void listen() {
    require(server_.listen(config_.at("bindHost").get<std::string>(), config_.at("port").get<int>()),
            "authenticated node listener stopped");
  }

  bool send_message(const std::string& receiver, const Json& envelope) {
    const auto response = request_peer(receiver, "/v1/messages", envelope, 1024);
    return response && response->value("accepted", false);
  }

  std::optional<Json> request_peer(const std::string& receiver, const std::string& path, const Json& body, size_t maximum_reply) {
    const auto& peer = peers_.at(receiver);
    httplib::SSLClient client(peer.host, peer.port,
        config_.at("certificateFile"), config_.at("privateKeyFile"));
    client.set_ca_cert_path(config_.at("caFile"));
    client.enable_system_ca(false);
    client.enable_server_certificate_verification(true);
    client.enable_server_hostname_verification(true);
    client.set_server_certificate_verifier([&](const httplib::tls::VerifyContext& context) {
      return context.preverify_ok && (context.depth != 0 || fingerprint(static_cast<const X509*>(context.cert)) == receiver);
    });
    require(SSL_CTX_set_min_proto_version(static_cast<SSL_CTX*>(client.tls_context()), TLS1_3_VERSION) == 1,
            "cannot enforce TLS 1.3");
    client.set_connection_timeout(2);
    client.set_read_timeout(2);
    client.set_write_timeout(2);
    client.set_payload_max_length(maximum_reply);
    auto response = client.Post(path, body.dump(), "application/json");
    if (!response || response->status != 200) return std::nullopt;
    return Json::parse(response->body);
  }

 private:
  Json config_;
  std::vector<std::string> authorization_keys_;
  EncryptedStore store_;
  httplib::SSLServer server_;
  std::string identity_;
  Parties parties_;
  std::map<std::string, Peer> peers_;
  int timeout_seconds_;
  std::mutex mutex_;
  std::map<std::string, std::shared_ptr<Session>> sessions_;
  std::shared_ptr<Session> active_;
  std::thread worker_;

  static void reply(httplib::Response& response, const Json& body) {
    response.set_header("Cache-Control", "no-store");
    response.set_content(body.dump(), "application/json");
  }

  void authorize(const std::string& caller, const Json& request, const std::string& operation) {
    const auto tenant = uuid(request, "tenantId");
    require(config_.at("clients").contains(caller), "client certificate is not authorized");
    const auto& authorization = config_.at("clients").at(caller);
    const auto tenants = authorization.at("tenants").get<std::vector<std::string>>();
    const auto operations = authorization.at("operations").get<std::vector<std::string>>();
    require(std::find(tenants.begin(), tenants.end(), tenant) != tenants.end() &&
                std::find(operations.begin(), operations.end(), operation) != operations.end(),
            "client is not authorized for this tenant and operation");
  }

  std::string context(const Json& descriptor) const {
    return Json{{"tenantId", uuid(descriptor, "tenantId")}, {"keyId", uuid(descriptor, "keyId")},
                {"partyId", identity_}}.dump();
  }

  std::string key_record(const Json& descriptor, const std::string& prefix) const {
    require(descriptor.at("epoch").is_number_integer() && descriptor.at("epoch") >= 1
        && descriptor.at("epoch") <= 1000000, "key epoch must be an integer within limits");
    const int epoch = descriptor.at("epoch").get<int>();
    return prefix + uuid(descriptor, "tenantId") + "-" + uuid(descriptor, "keyId") + "-" + std::to_string(epoch);
  }

  Json key_metadata(const Json& descriptor) {
    const auto aad = context(descriptor);
    const auto saved = store_.get(key_record(descriptor, "key-"), aad);
    require(saved.has_value(), "key is not registered");
    const auto metadata = Json::parse(saved->to_string());
    require(metadata.at("algorithm") == descriptor.at("algorithm") && metadata.at("parties") == Json(parties_),
            "registered key parameters differ");
    return metadata;
  }

  void signable_epoch(const Json& descriptor) {
    const auto aad = context(descriptor);
    const auto metadata = key_metadata(descriptor);
    require(!store_.get(key_record(descriptor, "refresh-fence-"), aad), "old epoch is fenced by its accepted refresh");
    if (descriptor.at("epoch").get<int>() > 1) {
      const auto ready = store_.get(key_record(descriptor, "ready-"), aad);
      require(ready && ready->to_string() == metadata.dump(), "refreshed epoch has not passed the holder readiness barrier");
    }
  }

  std::optional<Json> prepared_refresh(const Json& descriptor) {
    require(descriptor.at("operation") == "REFRESH" && descriptor.at("epoch").get<int>() > 1
        && descriptor.at("algorithm") != "MONERO_NATIVE_2_OF_3" && descriptor.at("messageHex") == ""
        && descriptor.at("online") == Json(parties_), "refresh readiness descriptor differs");
    algorithm(descriptor.at("algorithm"));
    const auto aad = context(descriptor);
    const auto original = store_.get("request-" + uuid(descriptor, "sessionId"), aad);
    if (!original) return std::nullopt;
    require(original->to_string() == descriptor.dump(), "refresh readiness request differs");
    const auto saved = store_.get(key_record(descriptor, "key-"), aad);
    if (!saved) return std::nullopt;
    const auto metadata = Json::parse(saved->to_string());
    require(metadata.at("algorithm") == descriptor.at("algorithm") && metadata.at("parties") == Json(parties_)
        && metadata.at("refreshSessionId") == descriptor.at("sessionId")
        && metadata.at("refreshIntentHash") == hash(descriptor.dump()), "prepared refresh identity differs");
    auto source = descriptor;
    source["epoch"] = descriptor.at("epoch").get<int>() - 1;
    const auto fence = store_.get(key_record(source, "refresh-fence-"), aad);
    require(fence && fence->to_string() == descriptor.dump()
        && key_metadata(source).at("publicKeyHex") == metadata.at("publicKeyHex"), "refresh source or public key differs");
    if (!store_.get(key_record(descriptor, "share-"), aad + metadata.dump())) return std::nullopt;
    return metadata;
  }

  std::optional<Json> completed_refresh(const Json& descriptor) {
    if (descriptor.at("operation") != "REFRESH") return std::nullopt;
    const auto metadata = prepared_refresh(descriptor);
    if (!metadata) return std::nullopt;
    const auto ready = store_.get(key_record(descriptor, "ready-"), context(descriptor));
    if (!ready) return std::nullopt;
    require(ready->to_string() == metadata->dump(), "refresh completion metadata differs");
    return Json{{"state", "COMPLETED"}, {"sessionId", descriptor.at("sessionId")}, {"tenantId", descriptor.at("tenantId")},
                {"keyId", descriptor.at("keyId")}, {"epoch", descriptor.at("epoch")},
                {"intentHash", hash(descriptor.dump())}, {"publicKeyHex", metadata->at("publicKeyHex")}};
  }

  void await_refresh_prepared(const std::shared_ptr<Session>& session, const Json& metadata) {
    while (Clock::now() < session->deadline) {
      bool ready = true;
      for (const auto& peer : session->online) {
        if (peer == identity_) continue;
        const auto status = request_peer(peer, "/v1/refresh-prepared", session->descriptor, 1024);
        ready = ready && status && status->value("prepared", false)
            && status->at("intentHash") == session->digest && status->at("publicKeyHex") == metadata.at("publicKeyHex");
      }
      if (ready) {
        store_.put(key_record(session->descriptor, "ready-"), context(session->descriptor), mem_t(metadata.dump()));
        return;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    throw std::runtime_error("refreshed shares are not durably prepared on all three holders");
  }

  Json result(const Json& query) {
    const auto id = uuid(query, "sessionId");
    const auto aad = context(query);
    if (auto recovered = store_.get("recovered-result-" + id, aad)) return Json::parse(recovered->to_string());
    if (auto existing = store_.get("result-" + id, aad)) return Json::parse(existing->to_string());
    std::lock_guard<std::mutex> lock(mutex_);
    const auto found = sessions_.find(id);
    if (found != sessions_.end()) {
      std::lock_guard<std::mutex> session_lock(found->second->mutex);
      return Json{{"state", found->second->finished ? "INTERRUPTED" : "RUNNING"}, {"sessionId", id}};
    }
    if (const auto saved = store_.get("request-" + id, aad)) {
      const auto descriptor = Json::parse(saved->to_string());
      if (auto completed = completed_refresh(descriptor)) {
        store_.put("recovered-result-" + id, aad, mem_t(completed->dump()));
        return *completed;
      }
      return Json{{"state", "INTERRUPTED"}, {"sessionId", id}};
    }
    // An admission interrupted before request persistence is still evidence that must fence a payment.
    if (store_.get("admission-" + id, aad)) return Json{{"state", "INTERRUPTED"}, {"sessionId", id}};
    return Json{{"state", "ABSENT"}, {"sessionId", id}};
  }

  Json submit(const Json& descriptor, const Json& proof) {
    const auto id = uuid(descriptor, "sessionId");
    const auto aad = context(descriptor);
    key_record(descriptor, "share-");
    const bool monero = descriptor.at("algorithm") == "MONERO_NATIVE_2_OF_3";
    if (!monero) algorithm(descriptor.at("algorithm"));
    const auto operation = descriptor.at("operation").get<std::string>();
    require(operation == "CREATE_KEY" || operation == "SIGN" || (!monero && operation == "REFRESH")
        || (monero && (operation == "MONERO_RELAY" || operation == "MONERO_OBSERVE")), "unsupported node operation");
    if (operation == "CREATE_KEY") require(descriptor.at("epoch") == 1, "DKG starts at epoch one");
    if (operation == "REFRESH") require(descriptor.at("epoch").get<int>() > 1 && descriptor.at("messageHex") == "", "invalid refresh target or message");
    if (monero) require(config_.contains("monero"), "native Monero is not configured");
    if (monero) require(descriptor.at("epoch") == 1, "native Monero uses its own epoch-one protocol");
    if (!monero && operation == "CREATE_KEY") require(descriptor.at("messageHex") == "", "DKG has no signing message");
    const auto online = descriptor.at("online").get<std::vector<std::string>>();
    require(online.size() == (operation == "CREATE_KEY" || operation == "REFRESH" ? 3 : operation == "MONERO_RELAY" ? 1 : 2), "invalid execution group size");
    std::set<std::string> distinct;
    for (const auto& party : online) require(peers_.count(party) && distinct.insert(party).second, "invalid execution party");
    auto self = std::find(online.begin(), online.end(), identity_);
    require(self != online.end(), "local node is not in execution group");
    if (operation == "CREATE_KEY" || operation == "REFRESH") require(std::equal(online.begin(), online.end(), parties_.begin()), "key ceremony party order differs");
    if (operation == "SIGN" || operation == "MONERO_RELAY" || operation == "MONERO_OBSERVE") {
      auto message = unhex(descriptor.at("messageHex"));
      if (monero) require(message.size() > 0 && message.size() <= 4096, "invalid native Monero signing policy size");
      else require(algorithm(descriptor.at("algorithm")) == Algorithm::Ed25519 || message.size() == 32, "signing digest must be 32 bytes");
    }
    const bool needs_authorization = operation == "SIGN" || operation == "MONERO_RELAY";
    Json authorization;
    if (needs_authorization) authorization = verify_authorization(proof, descriptor, authorization_keys_, 0, false);
    std::lock_guard<std::mutex> lock(mutex_);
    bool admitted = false;
    if (needs_authorization) {
      const auto grant_id = "approval-" + uuid(authorization, "authorizationId");
      if (auto pending = store_.get("admission-" + id, aad)) {
        require(pending->to_string() == Json{{"descriptor", descriptor}, {"authorization", proof}}.dump(), "original admission differs");
        admitted = true;
      }
      if (auto original = store_.get(grant_id, "SW-MPC-AUTHORIZATION-V1")) {
        require(original->to_string() == proof.dump(), "authorization was already bound to another execution");
        require(admitted, "authorization receipt is missing original admission evidence");
      }
      if (!admitted) {
        const auto now = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
        verify_authorization(proof, descriptor, authorization_keys_, now, true);
      }
    }
    bool recover_monero = false;
    bool recover_refresh = false;
    if (auto previous = store_.get("request-" + id, aad)) {
      require(previous->to_string() == descriptor.dump(), "session identity conflicts with existing request");
      if (needs_authorization) {
        require(admitted, "existing signing session has no independent authorization receipt");
        const auto original = store_.get("session-approval-" + id, aad);
        require(original && original->to_string() == proof.dump(), "original session authorization differs");
      }
      if (auto recovered = store_.get("recovered-result-" + id, aad)) return Json::parse(recovered->to_string());
      std::optional<Json> outcome;
      if (auto saved = store_.get("result-" + id, aad)) {
        outcome = Json::parse(saved->to_string());
        if (outcome->at("state") == "COMPLETED"
            || ((!monero || operation != "SIGN") && operation != "REFRESH")) return *outcome;
      }
      const auto current = sessions_.find(id);
      if (current != sessions_.end()) {
        std::lock_guard<std::mutex> session_lock(current->second->mutex);
        if (!current->second->finished) return Json{{"state", "RUNNING"}, {"sessionId", id}};
      }
      if (operation == "REFRESH") {
        if (auto completed = completed_refresh(descriptor)) {
          store_.put("recovered-result-" + id, aad, mem_t(completed->dump()));
          return *completed;
        }
        if (!prepared_refresh(descriptor)) return outcome.value_or(Json{{"state", "INTERRUPTED"}, {"sessionId", id}});
        recover_refresh = true; // Retry public readiness only; never repeat the random refresh protocol.
      } else {
        if (!monero || operation != "SIGN" || !store_.get("monero-reviewed-" + id, aad + descriptor.dump()))
          return outcome.value_or(Json{{"state", "INTERRUPTED"}, {"sessionId", id}});
        recover_monero = true;
      }
    }
    if (!monero && operation == "SIGN") signable_epoch(descriptor);
    // No receipt or protocol state is created on capacity rejection.
    if (active_) return Json{{"state", "BUSY"}, {"sessionId", id}};
    if (worker_.joinable()) worker_.join();
    for (auto current = sessions_.begin(); current != sessions_.end();) {
      const auto retained = current->second;
      std::lock_guard<std::mutex> session_lock(retained->mutex);
      if (retained->finished && Clock::now() >= retained->deadline) current = sessions_.erase(current);
      else ++current;
    }
    if (sessions_.size() >= 128 && !sessions_.count(id)) return Json{{"state", "BUSY"}, {"sessionId", id}};
    if (operation == "CREATE_KEY") require(!store_.get(key_record(descriptor, "key-"), aad), "key identity already exists");
    if (operation == "REFRESH" && !recover_refresh) {
      auto source = descriptor;
      source["epoch"] = descriptor.at("epoch").get<int>() - 1;
      const auto fence = store_.get(key_record(source, "refresh-fence-"), aad);
      if (fence) require(fence->to_string() == descriptor.dump(), "source epoch already has another refresh");
      else signable_epoch(source);
      require(!store_.get(key_record(descriptor, "key-"), aad), "refresh target epoch already exists");
      // The fence precedes acceptance/crypto and survives crashes and missing replies.
      store_.put(key_record(source, "refresh-fence-"), aad, mem_t(descriptor.dump()));
    }
    if (needs_authorization) {
      // Admission and ABSENT reads share mutex_: an expired queued request cannot appear after a negative audit.
      if (!admitted) {
        const auto now = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
        verify_authorization(proof, descriptor, authorization_keys_, now, true);
      }
      // This first durable marker makes every crash point report INTERRUPTED, never a misleading ABSENT.
      store_.put("admission-" + id, aad, mem_t(Json{{"descriptor", descriptor}, {"authorization", proof}}.dump()));
#ifdef WALLET_MPC_TEST_FAILPOINTS
      if (std::getenv("WALLET_MPC_TEST_EXIT_AFTER_ADMISSION")) _exit(86);
#endif
      // No crypto begins before both one-time use and original-session binding are durable.
      store_.put("approval-" + uuid(authorization, "authorizationId"), "SW-MPC-AUTHORIZATION-V1", mem_t(proof.dump()));
      store_.put("session-approval-" + id, aad, mem_t(proof.dump()));
    }
    store_.put("request-" + id, aad, mem_t(descriptor.dump()));
    auto session = std::make_shared<Session>();
    session->descriptor = descriptor;
    session->digest = hash(descriptor.dump());
    session->online = online;
    session->self = static_cast<int>(self - online.begin());
    session->recover_monero = recover_monero;
    session->recover_refresh = recover_refresh;
    session->deadline = Clock::now() + std::chrono::seconds(timeout_seconds_);
    sessions_.insert_or_assign(id, session);
    active_ = session;
    worker_ = std::thread([this, session] { execute(session); });
    return Json{{"state", "RUNNING"}, {"sessionId", id}};
  }

  void receive_message(const std::string& sender, const Json& envelope) {
    require(peers_.count(sender) && sender != identity_, "unknown protocol peer");
    const auto id = uuid(envelope, "sessionId");
    std::shared_ptr<Session> session;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      const auto found = sessions_.find(id);
      require(found != sessions_.end(), "protocol session is not registered");
      session = found->second;
    }
    require(std::find(session->online.begin(), session->online.end(), sender) != session->online.end() &&
                envelope.at("intentHash") == session->digest, "protocol message context differs");
    const int sequence = envelope.at("sequence");
    const auto encoded = envelope.at("payloadHex").get<std::string>();
    auto payload = unhex(encoded, 4 * 1024 * 1024);
    const auto digest = hash(encoded);
    std::lock_guard<std::mutex> lock(session->mutex);
    auto& seen = session->received_hashes[sender];
    require(sequence >= 0 && sequence <= static_cast<int>(seen.size()), "protocol message is out of order");
    if (sequence < static_cast<int>(seen.size())) {
      require(seen[sequence] == digest, "protocol message replay differs");
      return;
    }
    require(!session->finished && Clock::now() < session->deadline && seen.size() < 512 &&
                session->queued_bytes + payload.size() <= 32 * 1024 * 1024, "protocol session limit reached");
    seen.push_back(digest);
    session->queued_bytes += payload.size();
    session->incoming[sender].push_back(std::move(payload));
    session->ready.notify_all();
  }

  void execute(const std::shared_ptr<Session>& session) {
    const auto& descriptor = session->descriptor;
    const auto id = uuid(descriptor, "sessionId");
    const auto aad = context(descriptor);
    Json outcome{{"sessionId", id}, {"tenantId", descriptor.at("tenantId")},
                 {"keyId", descriptor.at("keyId")}, {"epoch", descriptor.at("epoch")}, {"intentHash", session->digest}};
    try {
      PeerTransport transport(*this, session);
      if (descriptor.at("algorithm") == "MONERO_NATIVE_2_OF_3") {
        auto exchange = [&](const Json& local) {
          const auto encoded = local.dump();
          for (int peer = 0; peer < static_cast<int>(session->online.size()); ++peer)
            if (peer != session->self && transport.send(peer, mem_t(encoded)) != SUCCESS)
              throw std::runtime_error("native Monero ceremony message failed");
          std::vector<Json> values;
          for (int peer = 0; peer < static_cast<int>(session->online.size()); ++peer) {
            if (peer == session->self) values.push_back(local);
            else {
              buf_t message;
              if (transport.receive(peer, message) != SUCCESS) throw std::runtime_error("native Monero ceremony reply failed");
              values.push_back(Json::parse(message.to_string()));
            }
          }
          return values;
        };
        if (descriptor.at("operation") == "CREATE_KEY")
          outcome.update(create_monero_key(descriptor, config_.at("monero"), identity_, store_, exchange));
        else if (descriptor.at("operation") == "SIGN") {
          if (session->recover_monero) {
            outcome.update(recover_monero_payment(descriptor, config_.at("monero"), identity_, session->self, store_, [&](const std::string& cosigner) -> std::optional<Json> {
              const auto response = request_peer(cosigner, "/v1/monero-recovery-material", descriptor, 1024 * 1024);
              if (!response || response->at("material").is_null()) return std::nullopt;
              return response->at("material");
            }));
          } else outcome.update(sign_monero_payment(descriptor, config_.at("monero"), identity_, session->self, store_, exchange));
        }
        else if (descriptor.at("operation") == "MONERO_RELAY")
          outcome.update(relay_monero_payment(descriptor, config_.at("monero"), identity_, store_));
        else outcome.update(observe_monero_account(descriptor, config_.at("monero"), identity_, store_, exchange));
      } else {
      const auto selected = algorithm(descriptor.at("algorithm"));
      const auto blob_id = key_record(descriptor, "share-");
      const auto metadata_id = key_record(descriptor, "key-");
      if (descriptor.at("operation") == "CREATE_KEY") {
        auto key = generate(selected, parties_, session->self, transport);
        const auto pub = public_key(key);
        const Json metadata{{"algorithm", descriptor.at("algorithm")}, {"parties", parties_}, {"publicKeyHex", hex(pub)}};
        store_.put(blob_id, aad + metadata.dump(), key.blob);
        store_.put(metadata_id, aad, mem_t(metadata.dump()));
        outcome["publicKeyHex"] = hex(pub);
      } else if (descriptor.at("operation") == "REFRESH") {
        Json metadata;
        if (session->recover_refresh) {
          metadata = *prepared_refresh(descriptor);
        } else {
          auto source = descriptor;
          source["epoch"] = descriptor.at("epoch").get<int>() - 1;
          const auto original = key_metadata(source);
          auto blob = store_.get(key_record(source, "share-"), aad + original.dump());
          require(blob.has_value(), "original refresh share is unavailable");
          KeyShare old_key(selected, parties_, std::move(*blob));
          auto next = refresh(old_key, session->self, transport);
          require(hex(public_key(next)) == original.at("publicKeyHex"), "refresh changed the joint public key");
          metadata = Json{{"algorithm", descriptor.at("algorithm")}, {"parties", parties_},
                          {"publicKeyHex", original.at("publicKeyHex")}, {"refreshSessionId", id},
                          {"refreshIntentHash", session->digest}};
          store_.put(blob_id, aad + metadata.dump(), next.blob);
          store_.put(metadata_id, aad, mem_t(metadata.dump()));
        }
        await_refresh_prepared(session, metadata);
        outcome["publicKeyHex"] = metadata.at("publicKeyHex");
      } else {
        const auto metadata = key_metadata(descriptor);
        auto blob = store_.get(blob_id, aad + metadata.dump());
        require(blob.has_value(), "local key share is unavailable");
        KeyShare key(selected, parties_, std::move(*blob));
        const auto message = unhex(descriptor.at("messageHex"));
        auto signature = sign(key, session->online, session->self, message, transport);
        outcome["publicKeyHex"] = metadata.at("publicKeyHex");
        outcome["signatureHex"] = hex(signature);
      }
      }
      outcome["state"] = "COMPLETED";
      store_.put((session->recover_monero || session->recover_refresh ? "recovered-result-" : "result-") + id, aad, mem_t(outcome.dump()));
    } catch (const std::exception& failure) {
      // A failed or lost result must never be interpreted as proof that no
      // usable signature was produced. The wallet must reconcile this state.
      outcome["state"] = descriptor.at("operation") == "SIGN" || descriptor.at("operation") == "MONERO_RELAY"
          ? "INDETERMINATE" : "FAILED";
      outcome.erase("signatureHex");
      outcome.erase("publicKeyHex");
      outcome.erase("publicAddress");
      outcome.erase("monero");
      try { if (!store_.get("result-" + id, aad)) store_.put("result-" + id, aad, mem_t(outcome.dump())); } catch (...) {}
      std::cerr << "MPC session did not complete: " << id << '\n';
      if (const auto* native = dynamic_cast<const MoneroError*>(&failure)) std::cerr << native->what() << '\n';
    }
    {
      std::lock_guard<std::mutex> lock(session->mutex);
      session->finished = true;
      session->incoming.clear();
      session->queued_bytes = 0;
      session->ready.notify_all();
    }
    std::lock_guard<std::mutex> lock(mutex_);
    active_.reset();
  }
};

error_t PeerTransport::send(api::party_idx_t receiver, mem_t message) {
  try {
    require(receiver >= 0 && receiver < static_cast<int>(session_->online.size()) && receiver != session_->self,
            "invalid protocol recipient");
    Json envelope{{"sessionId", session_->descriptor.at("sessionId")}, {"intentHash", session_->digest},
                  {"sequence", next_sequence_[receiver]}, {"payloadHex", hex(message)}};
    while (Clock::now() < session_->deadline) {
      if (node_.send_message(session_->online[receiver], envelope)) { ++next_sequence_[receiver]; return SUCCESS; }
      std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
  } catch (const std::exception&) {}
  return E_NET_GENERAL;
}

error_t PeerTransport::receive(api::party_idx_t sender, buf_t& message) {
  if (sender < 0 || sender >= static_cast<int>(session_->online.size()) || sender == session_->self) return E_BADARG;
  const auto& identity = session_->online[sender];
  std::unique_lock<std::mutex> lock(session_->mutex);
  if (!session_->ready.wait_until(lock, session_->deadline, [&] {
        return session_->finished || !session_->incoming[identity].empty();
      }) || session_->finished) return E_NET_GENERAL;
  message = std::move(session_->incoming[identity].front());
  session_->incoming[identity].pop_front();
  session_->queued_bytes -= message.size();
  return SUCCESS;
}

error_t PeerTransport::receive_all(const std::vector<api::party_idx_t>& senders, std::vector<buf_t>& messages) {
  messages.resize(senders.size());
  for (size_t i = 0; i < senders.size(); ++i) {
    const auto status = receive(senders[i], messages[i]);
    if (status != SUCCESS) return status;
  }
  return SUCCESS;
}
}  // namespace

int main(int argc, char** argv) {
  if (argc != 2) { std::cerr << "usage: wallet-mpc-signer node-config.json\n"; return 2; }
  const rlimit no_core{0, 0};
  if (setrlimit(RLIMIT_CORE, &no_core) != 0) { std::cerr << "cannot disable core dumps\n"; return 1; }
  umask(0077);
  try {
    Node node(read_json_file(argv[1]));
    node.listen();
    return 0;
  } catch (const std::invalid_argument& error) {
    std::cerr << "MPC signer rejected configuration: " << error.what() << '\n';
    return 1;
  } catch (const std::runtime_error& error) {
    std::cerr << "MPC signer storage or native initialization failed: " << error.what() << '\n';
    return 1;
  } catch (const std::exception&) {
    std::cerr << "MPC signer initialization or listener failed\n";
    return 1;
  }
}
