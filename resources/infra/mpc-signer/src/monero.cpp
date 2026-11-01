#include <wallet_mpc/monero.h>
#include <wallet_mpc/monero_policy.h>
#include <httplib.h>
#include <openssl/rand.h>
#include <openssl/evp.h>
#include <algorithm>
#include <cerrno>
#include <fcntl.h>
#include <fstream>
#include <memory>
#include <map>
#include <optional>
#include <sstream>
#include <iomanip>
#include <limits>
#include <set>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

namespace wallet_mpc {
namespace {
using Json = nlohmann::json;
using coinbase::mem_t;
void require(bool valid, const char* message) { if (!valid) throw MoneroError(message); }
std::string hex(const std::string& value) {
  static constexpr char digits[] = "0123456789abcdef";
  std::string output; output.reserve(value.size() * 2);
  for (const auto character : value) {
    const auto octet = static_cast<unsigned char>(character);
    output += digits[octet >> 4]; output += digits[octet & 15];
  }
  return output;
}
std::string text(const std::string& value, size_t maximum = 8192) {
  require(value.size() % 2 == 0 && value.size() <= maximum, "invalid native Monero payload size");
  auto digit = [](char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    throw std::invalid_argument("invalid native Monero payload hex");
  };
  std::string output;
  for (size_t i = 0; i < value.size(); i += 2) output += static_cast<char>(digit(value[i]) * 16 + digit(value[i + 1]));
  return output;
}
std::string sha256(const std::string& value) {
  unsigned char digest[32]; size_t length = sizeof(digest);
  require(EVP_Q_digest(nullptr, "SHA256", nullptr, value.data(), value.size(), digest, &length) == 1 && length == sizeof(digest),
      "native Monero intent hash is unavailable");
  return hex(std::string(reinterpret_cast<const char*>(digest), length));
}
uint64_t atomic(const Json& value) {
  const auto number = value.get<std::string>();
  require(!number.empty() && number.size() <= 20 && (number.size() == 1 || number[0] != '0')
      && std::all_of(number.begin(), number.end(), [](char c) { return c >= '0' && c <= '9'; }), "invalid native Monero atomic amount");
  return std::stoull(number);
}
uint64_t integer(const Json& value) {
  require(value.is_number_unsigned() || (value.is_number_integer() && value.get<int64_t>() >= 0), "native Monero fact is not an unsigned integer");
  return value.get<uint64_t>();
}
std::string uuid(const Json& object, const char* name) {
  const auto value = object.at(name).get<std::string>();
  require(value.size() == 36, "invalid native Monero key identity");
  for (size_t i = 0; i < value.size(); ++i) {
    const bool hyphen = i == 8 || i == 13 || i == 18 || i == 23;
    require(hyphen ? value[i] == '-' : (value[i] >= '0' && value[i] <= '9') ||
        (value[i] >= 'a' && value[i] <= 'f'), "invalid native Monero key identity");
  }
  return value;
}
std::string context(const std::string& tenant, const std::string& key, const std::string& party) {
  return Json{{"tenantId", tenant}, {"keyId", key}, {"partyId", party}}.dump();
}
std::string record(const std::string& tenant, const std::string& key) { return "key-" + tenant + "-" + key + "-1"; }

Json credentials(const std::string& path) {
  int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
  require(fd >= 0, "cannot read native Monero RPC credential file");
  struct Guard { int fd; ~Guard() { close(fd); } } guard{fd};
  struct stat status{};
  require(fstat(fd, &status) == 0 && S_ISREG(status.st_mode) && status.st_uid == geteuid()
      && (status.st_mode & 0777) == 0600 && status.st_size > 0 && status.st_size <= 8192,
      "native Monero credentials must be an owner-only regular file");
  std::string bytes(static_cast<size_t>(status.st_size), '\0');
  size_t offset = 0;
  while (offset < bytes.size()) {
    const auto received = read(fd, bytes.data() + offset, bytes.size() - offset);
    if (received < 0 && errno == EINTR) continue;
    require(received > 0, "cannot load native Monero RPC credentials");
    offset += static_cast<size_t>(received);
  }
  auto parsed = Json::parse(bytes);
  require(parsed.at("username").is_string() && !parsed.at("username").get<std::string>().empty()
      && parsed.at("password").is_string() && !parsed.at("password").get<std::string>().empty(),
      "native Monero RPC credentials are absent");
  return parsed;
}
class Rpc {
 public:
  Rpc(const Json& configuration, const char* endpoint, const char* credential_file, bool wallet)
      : client_(configuration.at(endpoint).get<std::string>()) {
    const auto url = configuration.at(endpoint).get<std::string>();
    const std::string prefix = "http://127.0.0.1:";
    require(url.compare(0, prefix.size(), prefix) == 0 && url.find_first_not_of("0123456789", prefix.size()) == std::string::npos,
        "native Monero RPC must be a loopback HTTP origin");
    const auto port = std::stoul(url.substr(prefix.size()));
    require(port > 0 && port <= 65535, "invalid native Monero RPC port");
    client_.set_connection_timeout(2); client_.set_read_timeout(15); client_.set_write_timeout(5);
    client_.set_payload_max_length(4 * 1024 * 1024);
    client_.set_keep_alive(true);
    if (configuration.contains(credential_file)) {
      credentials_ = credentials(configuration.at(credential_file));
    } else if (wallet) {
      require(configuration.value("allowInsecureTestRpc", false) && configuration.at("network") == "regtest",
          "native Monero wallet RPC authentication is required");
    }
  }
  Json call(const std::string& method, const Json& parameters = Json::object()) {
    const auto body = Json{{"jsonrpc", "2.0"}, {"id", "wallet-mpc"}, {"method", method}, {"params", parameters}}.dump();
    auto result = post("/json_rpc", body);
    // Never include RPC response bodies in errors: ceremony material and wallet passwords are private.
    if ((result.contains("error") && !result.at("error").is_null()) || !result.contains("result"))
      throw MoneroError("native Monero RPC rejected operation: " + method);
    return result.at("result");
  }
  Json raw(const std::string& path, const Json& parameters) { return post(path, parameters.dump()); }
  bool open(const std::string& file, const std::string& password) {
    try { call("close_wallet"); } catch (const std::invalid_argument&) { }
    try { call("open_wallet", Json{{"filename", file}, {"password", password}}); return true; }
    catch (const std::invalid_argument&) { return false; }
  }
 private:
  Json post(const std::string& path, const std::string& body) {
    auto response = client_.Post(path, authorization(path), body, "application/json");
    if (response && response->status == 401 && credentials_) {
      std::map<std::string, std::string> challenge;
      require(httplib::detail::parse_www_authenticate(*response, challenge, false), "native Monero Digest challenge is absent");
      require(challenge.count("nonce") && challenge.count("realm") && challenge.count("qop")
          && challenge.at("nonce").size() <= 512 && challenge.at("realm").size() <= 256
          && challenge.at("qop") == "auth", "unsupported native Monero Digest challenge");
      const auto algorithm = challenge.count("algorithm") ? challenge.at("algorithm") : "MD5";
      require(algorithm == "MD5" || algorithm == "MD5-sess", "unsupported native Monero Digest algorithm");
      challenge_ = std::move(challenge); counter_ = 0;
      std::string nonce(24, '\0');
      require(RAND_bytes(reinterpret_cast<unsigned char*>(nonce.data()), static_cast<int>(nonce.size())) == 1,
          "native Monero Digest randomness failed");
      client_nonce_ = hex(nonce);
      response = client_.Post(path, authorization(path), body, "application/json");
    }
    require(response && response->status == 200, "native Monero RPC unavailable");
    return Json::parse(response->body);
  }
  httplib::Client client_;
  std::optional<Json> credentials_;
  std::map<std::string, std::string> challenge_;
  std::string client_nonce_;
  uint32_t counter_ = 0;
  static std::string md5(const std::string& input) {
    unsigned char result[16]; size_t length = sizeof(result);
    require(EVP_Q_digest(nullptr, "MD5", nullptr, input.data(), input.size(), result, &length) == 1 && length == sizeof(result),
        "native Monero Digest hash is unavailable");
    return hex(std::string(reinterpret_cast<const char*>(result), length));
  }
  httplib::Headers authorization(const std::string& path) {
    if (challenge_.empty()) return {};
    require(credentials_.has_value() && counter_ < 0xffffffff, "native Monero Digest session exhausted");
    const auto username = credentials_->at("username").get<std::string>();
    const auto password = credentials_->at("password").get<std::string>();
    const auto& nonce = challenge_.at("nonce"); const auto& realm = challenge_.at("realm");
    const auto algorithm = challenge_.count("algorithm") ? challenge_.at("algorithm") : "MD5";
    auto first = md5(username + ":" + realm + ":" + password);
    if (algorithm == "MD5-sess") first = md5(first + ":" + nonce + ":" + client_nonce_);
    std::ostringstream encoded; encoded << std::setfill('0') << std::setw(8) << std::hex << ++counter_;
    const auto nc = encoded.str();
    const auto response = md5(first + ":" + nonce + ":" + nc + ":" + client_nonce_ + ":auth:" + md5("POST:" + path));
    const auto quote = [](const std::string& value) { return "\"" + httplib::detail::escape_quoted_pairs(value) + "\""; };
    std::string header = "Digest username=" + quote(username) + ", realm=" + quote(realm) + ", nonce=" + quote(nonce)
        + ", uri=" + quote(path) + ", algorithm=" + algorithm + ", qop=auth, nc=" + nc
        + ", cnonce=" + quote(client_nonce_) + ", response=" + quote(response);
    if (challenge_.count("opaque")) header += ", opaque=" + quote(challenge_.at("opaque"));
    return {{"Authorization", header}};
  }
};
std::string password(EncryptedStore& store, const std::string& id, const std::string& aad) {
  if (const auto existing = store.get(id, aad)) return existing->to_string();
  std::string bytes(32, '\0');
  require(RAND_bytes(reinterpret_cast<unsigned char*>(bytes.data()), static_cast<int>(bytes.size())) == 1,
      "native Monero password randomness failed");
  const auto value = hex(bytes); store.put(id, aad, mem_t(value)); return value;
}
std::string public_address(Rpc& rpc, int account, const std::string& network) {
  const auto response = rpc.call("get_address", Json{{"account_index", account}});
  const auto address = response.at("address").get<std::string>();
  require(address.size() == 95 && std::all_of(address.begin(), address.end(), [](char value) {
    const std::string alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    return alphabet.find(value) != std::string::npos;
  }), "native Monero returned an invalid public address");
  const auto validation = rpc.call("validate_address", Json{{"address", address}, {"any_net_type", false}, {"allow_openalias", false}});
  require(validation.at("valid").get<bool>() && !validation.at("integrated").get<bool>()
      && validation.at("subaddress").get<bool>() == (account > 0)
      && validation.at("nettype") == (network == "regtest" ? "mainnet" : network),
      "native Monero wallet address network or account type differs");
  return address;
}
std::string public_key_hex(const std::string& address) {
  require(address.size() == 95, "native Monero key requires a standard or subaddress encoding");
  const std::string alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  std::string decoded;
  for (size_t start = 0; start < address.size(); start += 11) {
    const auto characters = std::min<size_t>(11, address.size() - start);
    const size_t bytes = characters == 11 ? 8 : 5;
    require(characters == 11 || characters == 7, "native Monero base58 block differs");
    uint64_t number = 0;
    for (size_t i = 0; i < characters; ++i) {
      const auto digit = alphabet.find(address[start + i]);
      require(digit != std::string::npos && number <= (std::numeric_limits<uint64_t>::max() - digit) / 58,
          "native Monero public address block overflows");
      number = number * 58 + digit;
    }
    require(bytes == 8 || number < (uint64_t{1} << (bytes * 8)), "native Monero final address block overflows");
    std::string block(bytes, '\0');
    for (size_t i = bytes; i > 0; --i) { block[i - 1] = static_cast<char>(number & 255); number >>= 8; }
    decoded += block;
  }
  require(decoded.size() == 69, "native Monero public address payload differs");
  return hex(decoded.substr(1, 64));
}
void agree(const std::vector<Json>& values, const Json& expected) {
  require(values.size() == 3 && std::all_of(values.begin(), values.end(), [&](const auto& item) { return item == expected; }),
      "native Monero ceremony participants disagree");
}
std::string hash_field(const Json& value) {
  const auto encoded = value.get<std::string>();
  require(encoded.size() == 64, "invalid native Monero public hash or output key");
  text(encoded); return encoded;
}
Json header(Rpc& daemon, uint64_t height) {
  const auto result = daemon.call("get_block_header_by_height", Json{{"height", height}});
  const auto item = result.at("block_header");
  require(result.value("status", "") == "OK" && !result.value("untrusted", false)
      && integer(item.at("height")) == height && !item.at("orphan_status").get<bool>(), "native Monero canonical header is unavailable");
  hash_field(item.at("hash")); return item;
}
struct ObservedTransaction { Json facts, transaction, indices; };
ObservedTransaction transaction_state(Rpc& daemon, const std::string& tx_hash) {
  const auto response = daemon.raw("/get_transactions", Json{{"txs_hashes", Json::array({tx_hash})}, {"decode_as_json", true}, {"prune", true}});
  require(response.at("status") == "OK" && !response.value("untrusted", false), "native Monero transaction lookup is unavailable");
  const auto transactions = response.value("txs", Json::array());
  Json result{{"transactionHash", tx_hash}, {"known", false}};
  if (transactions.empty()) {
    require(response.at("missed_tx") == Json::array({tx_hash}), "native Monero transaction lookup omitted its exact absence observation");
    return {result, Json(), Json()};
  }
  require(transactions.size() == 1 && transactions.at(0).at("tx_hash") == tx_hash
      && response.value("missed_tx", Json::array()).empty(), "native Monero transaction lookup returned a different identity");
  const auto transaction = transactions.at(0);
  const auto original = Json::parse(transaction.at("as_json").get<std::string>());
  require(original.at("vin").is_array() && !original.at("vin").empty() && original.at("vout").is_array()
      && original.at("vout").size() <= 10000, "native Monero original transaction is unavailable or oversized");
  const bool coinbase = original.at("vin").size() == 1 && original.at("vin").at(0).contains("gen");
  require(coinbase || std::none_of(original.at("vin").begin(), original.at("vin").end(),
      [](const auto& input) { return input.contains("gen"); }), "native Monero coinbase input identity differs");
  result["known"] = true; result["inPool"] = transaction.at("in_pool");
  result["doubleSpendSeen"] = transaction.at("double_spend_seen");
  result["coinbase"] = coinbase; result["unlockTime"] = integer(original.at("unlock_time"));
  if (!transaction.at("in_pool").get<bool>()) {
    const auto height = integer(transaction.at("block_height"));
    const auto block = header(daemon, height);
    // Bind inclusion to the canonical block's original transaction list, not only an index height.
    const auto full = daemon.call("get_block", Json{{"hash", block.at("hash")}});
    const auto hashes = full.value("tx_hashes", Json::array());
    require(full.value("status", "") == "OK" && !full.value("untrusted", false)
        && full.at("block_header").at("hash") == block.at("hash")
        && integer(full.at("block_header").at("height")) == height
        && (coinbase ? full.at("miner_tx_hash") == tx_hash && block.at("miner_tx_hash") == tx_hash
            && integer(original.at("vin").at(0).at("gen").at("height")) == height
            : std::find(hashes.begin(), hashes.end(), tx_hash) != hashes.end()),
        "native Monero original canonical block omits this transaction");
    require(transaction.at("output_indices").is_array()
        && transaction.at("output_indices").size() == original.at("vout").size(), "native Monero output index evidence is incomplete");
    result["blockHeight"] = height; result["blockHash"] = block.at("hash");
    result["confirmations"] = integer(transaction.at("confirmations"));
  }
  return {result, original, transaction.value("output_indices", Json::array())};
}
void bound_policy(const Json& descriptor, const Json& metadata, const Json& policy) {
  require(policy.at("network") == metadata.at("network") && policy.at("genesisHash") == metadata.at("genesisHash")
      && policy.at("sender") == metadata.at("address"), "native Monero observation or relay policy differs from the registered account");
  for (const auto& party : descriptor.at("online"))
    require(std::find(metadata.at("parties").begin(), metadata.at("parties").end(), party) != metadata.at("parties").end(),
        "native Monero operation party is not registered");
}
Json open_address(const Json& descriptor, const Json& configuration, const std::string& local_party, EncryptedStore& store, Rpc& rpc) {
  const auto tenant = uuid(descriptor, "tenantId"), key = uuid(descriptor, "keyId");
  const auto stored = store.get(record(tenant, key), context(tenant, key, local_party));
  require(stored.has_value(), "native Monero custody address is unavailable");
  const auto metadata = Json::parse(stored->to_string());
  require(metadata.at("algorithm") == "MONERO_NATIVE_2_OF_3" && metadata.at("kind") == "ADDRESS"
      && metadata.at("network") == configuration.at("network"), "native Monero custody address identity differs");
  const auto root = uuid(metadata, "rootKeyId"), root_aad = context(tenant, root, local_party);
  const auto parent_record = store.get(record(tenant, root), root_aad);
  require(parent_record.has_value(), "native Monero parent is unavailable");
  const auto parent = Json::parse(parent_record->to_string());
  require(parent.at("kind") == "ROOT" && parent.at("algorithm") == metadata.at("algorithm")
      && parent.at("network") == metadata.at("network") && parent.at("genesisHash") == metadata.at("genesisHash")
      && parent.at("parties") == metadata.at("parties"), "native Monero parent binding differs");
  Rpc daemon(configuration, "daemonRpcUrl", "daemonCredentialFile", false);
  const auto info = daemon.call("get_info");
  const auto network = metadata.at("network").get<std::string>();
  require(info.at("nettype") == (network == "regtest" ? "fakechain" : network)
      && (network != "regtest" || info.at("offline").get<bool>())
      && daemon.call("get_block_header_by_height", Json{{"height", 0}}).at("block_header").at("hash") == metadata.at("genesisHash"),
      "native Monero daemon changed its network identity");
  const auto secret = store.get("monero-password-" + tenant + "-" + root, root_aad);
  require(secret.has_value() && rpc.open("mpc-" + root, secret->to_string()), "native Monero registered wallet is unavailable");
  require(public_address(rpc, 0, network) == parent.at("address")
      && public_address(rpc, metadata.at("accountIndex"), network) == metadata.at("address"), "native Monero registered wallet public identity changed");
  return metadata;
}

Json review_payment(Rpc& rpc, const Json& metadata, const Json& policy, const std::string& txset, const Json& owned) {
  // Leave room for descriptions and the hex-wrapped result in the existing 1 MiB store.
  require(txset.size() <= 256 * 1024 && !txset.empty(), "native Monero transaction set is absent or oversized");
  text(txset, 256 * 1024);
  const auto descriptions = rpc.call("describe_transfer", Json{{"multisig_txset", txset}}).at("desc");
  require(descriptions.is_array() && descriptions.size() == 1, "native Monero payment must contain exactly one transaction");
  const auto description = descriptions.at(0);
  const auto amount = atomic(policy.at("amountAtomic")), maximum_fee = atomic(policy.at("maximumFeeAtomic"));
  const auto fee = integer(description.at("fee")), inputs = integer(description.at("amount_in")), change = integer(description.at("change_amount"));
  require(amount > 0 && integer(description.at("unlock_time")) == 0 && integer(description.at("ring_size")) == 16
      && fee <= maximum_fee && inputs >= fee && inputs - fee >= amount && inputs - fee - amount == change
      && integer(description.at("amount_out")) == inputs - fee
      && change > 0 && description.at("change_address") == metadata.at("address"), "native Monero amount, fee, unlock time or original change scope differs");
  const auto recipients = description.at("recipients");
  require(recipients.is_array() && recipients.size() == 1 && recipients.at(0).at("address") == policy.at("recipient")
      && integer(recipients.at(0).at("amount")) == amount, "native Monero exact recipient differs");
  const auto validation = rpc.call("validate_address", Json{{"address", policy.at("recipient")}, {"any_net_type", false}, {"allow_openalias", false}});
  require(validation.at("valid").get<bool>(), "native Monero recipient is invalid for this network");
  if (!validation.at("integrated").get<bool>()) {
    const auto payment_id = description.at("payment_id").get<std::string>();
    require(std::all_of(payment_id.begin(), payment_id.end(), [](char c) { return c == '0'; }), "native Monero payment has an unapproved payment ID");
  }
  const auto sources = description.at("sources");
  require(sources.is_array() && !sources.empty() && sources.size() <= 1000, "native Monero exact source list is absent or oversized");
  std::set<std::string> unique;
  uint64_t total = 0;
  for (const auto& source : sources) {
    // Official 0.18.5.4 describes the real ring member as ctkey: 32-byte output key + 32-byte commitment.
    const auto encoded = source.at("pubkey").get<std::string>();
    require(encoded.size() == 128, "native Monero real ring member encoding differs");
    text(encoded);
    const auto output_key = encoded.substr(0, 64);
    require(unique.insert(output_key).second, "native Monero source output was duplicated");
    const auto match = std::find_if(owned.begin(), owned.end(), [&](const auto& output) {
      return output.at("pubkey") == output_key && output.at("global_index") == source.at("global_index")
          && output.at("amount") == source.at("amount") && !output.at("spent").template get<bool>()
          && !output.at("frozen").template get<bool>() && output.at("unlocked").template get<bool>()
          && output.at("subaddr_index").at("major") == metadata.at("accountIndex") && output.at("subaddr_index").at("minor") == 0;
    });
    require(match != owned.end(), "native Monero source is outside the selected owned unlocked account");
    const auto value = integer(source.at("amount")); require(value <= std::numeric_limits<uint64_t>::max() - total, "native Monero source amount overflow"); total += value;
  }
  require(total == inputs, "native Monero exact owned input amount differs");
  return canonical_monero_payment_description(description);
}

Json reviewed_owned_inputs(const Json& description, const Json& owned) {
  Json selected = Json::array();
  for (const auto& source : description.at("sources")) {
    const auto key = source.at("pubkey").get<std::string>().substr(0, 64);
    const auto match = std::find_if(owned.begin(), owned.end(), [&](const auto& output) {
      return output.at("pubkey") == key && output.at("global_index") == source.at("global_index");
    });
    require(match != owned.end(), "native Monero original reviewed input proof is unavailable");
    selected.push_back(Json{{"pubkey", match->at("pubkey")}, {"global_index", match->at("global_index")},
        {"amount", match->at("amount")}, {"spent", match->at("spent")}, {"frozen", match->at("frozen")},
        {"unlocked", match->at("unlocked")}, {"subaddr_index", match->at("subaddr_index")}});
  }
  return selected;
}

Json payment_artifact(const Json& descriptor, const Json& policy, const Json& selected, const Json& description) {
  const auto tx_hash = hash_field(selected.at("txHash"));
  return Json{{"protocol", "MONERO_NATIVE_2_OF_3"}, {"tenantId", uuid(descriptor, "tenantId")},
      {"keyId", uuid(descriptor, "keyId")}, {"sessionId", uuid(descriptor, "sessionId")},
      {"intentHash", sha256(descriptor.dump())}, {"policy", policy}, {"online", descriptor.at("online")},
      {"transactionHash", tx_hash}, {"transactionSetHex", selected.at("transactionSetHex")},
      {"feeAtomic", std::to_string(integer(description.at("fee")))}, {"description", description}};
}
}

Json create_monero_key(const Json& descriptor, const Json& configuration, const std::string& local_party,
                       EncryptedStore& store, const MoneroExchange& exchange) {
  require(descriptor.at("epoch") == 1 && descriptor.at("operation") == "CREATE_KEY", "invalid native Monero creation operation");
  const auto payload = Json::parse(text(descriptor.at("messageHex")));
  const auto tenant = uuid(descriptor, "tenantId"), key = uuid(descriptor, "keyId");
  const auto aad = context(tenant, key, local_party);
  const auto network = configuration.at("network").get<std::string>();
  require(std::set<std::string>{"mainnet", "testnet", "stagenet", "regtest"}.count(network) && payload.at("network") == network,
      "native Monero network differs");
  Rpc daemon(configuration, "daemonRpcUrl", "daemonCredentialFile", false);
  const auto information = daemon.call("get_info");
  require(information.at("nettype") == (network == "regtest" ? "fakechain" : network)
      && (network != "regtest" || information.at("offline").get<bool>()),
      "native Monero daemon network differs; regtest requires an isolated offline fakechain");
  const auto genesis = daemon.call("get_block_header_by_height", Json{{"height", 0}}).at("block_header").at("hash").get<std::string>();
  require(genesis.size() == 64 && payload.at("genesisHash") == genesis, "native Monero genesis differs");
  agree(exchange(Json{{"network", network}, {"genesisHash", genesis}}), Json{{"network", network}, {"genesisHash", genesis}});
  Rpc rpc(configuration, "walletRpcUrl", "walletCredentialFile", true);
  Json metadata{{"algorithm", "MONERO_NATIVE_2_OF_3"}, {"parties", descriptor.at("online")}, {"network", network}, {"genesisHash", genesis}};
  if (payload.at("kind") == "ROOT") {
    const auto secret = password(store, "monero-password-" + tenant + "-" + key, aad);
    const auto filename = "mpc-" + key;
    require(!store.get(record(tenant, key), aad), "native Monero root is already registered");
    if (!rpc.open(filename, secret)) rpc.call("create_wallet", Json{{"filename", filename}, {"password", secret}, {"language", "English"}});
    require(!rpc.call("is_multisig").at("multisig").get<bool>(), "interrupted native Monero root requires a new unpublished root identity");
    auto info = exchange(rpc.call("prepare_multisig", Json{{"enable_multisig_experimental", true}}).at("multisig_info"));
    info = exchange(rpc.call("make_multisig", Json{{"multisig_info", info}, {"threshold", 2}, {"password", secret}}).at("multisig_info"));
    for (int round = 0; round < 2; ++round)
      info = exchange(rpc.call("exchange_multisig_keys", Json{{"multisig_info", info}, {"password", secret}}).at("multisig_info"));
    const auto state = rpc.call("is_multisig");
    require(state.at("ready").get<bool>() && state.at("threshold") == 2 && state.at("total") == 3, "native Monero root ceremony is not ready");
    rpc.call("store");
    const auto address = public_address(rpc, 0, network); agree(exchange(address), address);
    metadata["kind"] = "ROOT"; metadata["address"] = address; metadata["publicKeyHex"] = public_key_hex(address);
  } else {
    require(payload.at("kind") == "ADDRESS", "unsupported native Monero key ceremony");
    const auto root = uuid(payload, "rootKeyId"); require(root != key, "native Monero child cannot be its root");
    const auto root_aad = context(tenant, root, local_party);
    const auto stored = store.get(record(tenant, root), root_aad); require(stored.has_value(), "native Monero root is unavailable");
    const auto parent = Json::parse(stored->to_string());
    require(parent.at("kind") == "ROOT" && parent.at("algorithm") == "MONERO_NATIVE_2_OF_3"
        && parent.at("genesisHash") == genesis && parent.at("network") == network && parent.at("parties") == descriptor.at("online"),
        "native Monero root identity differs");
    const auto secret = store.get("monero-password-" + tenant + "-" + root, root_aad);
    require(secret.has_value() && rpc.open("mpc-" + root, secret->to_string()), "native Monero root wallet is missing; restore its protected files");
    require(public_address(rpc, 0, network) == parent.at("address"), "native Monero root wallet differs from its registered public identity");
    const auto counts = exchange(rpc.call("get_accounts").at("subaddress_accounts").size());
    int next = 1;
    for (const auto& count : counts) { const auto current = count.get<int>(); require(current >= 1 && current <= 100000, "native Monero account bound exceeded"); next = std::max(next, current); }
    int current = static_cast<int>(rpc.call("get_accounts").at("subaddress_accounts").size());
    for (; current <= next; ++current) rpc.call("create_account", Json{{"label", current == next ? "mpc-" + key : "reserved-unpublished"}});
    rpc.call("store");
    const auto address = public_address(rpc, next, network); agree(exchange(Json{{"accountIndex", next}, {"address", address}}), Json{{"accountIndex", next}, {"address", address}});
    metadata["kind"] = "ADDRESS"; metadata["rootKeyId"] = root; metadata["accountIndex"] = next;
    metadata["address"] = address; metadata["publicKeyHex"] = public_key_hex(address);
  }
  store.put(record(tenant, key), aad, mem_t(metadata.dump()));
  return Json{{"publicKeyHex", metadata.at("publicKeyHex")}, {"publicAddress", metadata.at("address")}};
}

Json sign_monero_payment(const Json& descriptor, const Json& configuration, const std::string& local_party,
                         int self, EncryptedStore& store, const MoneroExchange& exchange) {
  require(descriptor.at("operation") == "SIGN" && descriptor.at("epoch") == 1 && descriptor.at("online").size() == 2
      && (self == 0 || self == 1), "invalid native Monero signing group");
  const auto policy = Json::parse(text(descriptor.at("messageHex")));
  require(policy.at("kind") == "PAYMENT", "unsupported native Monero signing policy");
  Rpc rpc(configuration, "walletRpcUrl", "walletCredentialFile", true);
  const auto metadata = open_address(descriptor, configuration, local_party, store, rpc);
  require(policy.at("network") == metadata.at("network") && policy.at("genesisHash") == metadata.at("genesisHash")
      && policy.at("sender") == metadata.at("address") && policy.at("recipient") != metadata.at("address")
      && atomic(policy.at("amountAtomic")) > 0, "native Monero exact signing network or sender differs");
  for (const auto& party : descriptor.at("online"))
    require(std::find(metadata.at("parties").begin(), metadata.at("parties").end(), party) != metadata.at("parties").end(), "native Monero signing party is not registered");
  rpc.call("refresh");
  const auto information = exchange(rpc.call("export_multisig_info").at("info"));
  rpc.call("import_multisig_info", Json{{"info", information}});
  const auto incoming = rpc.call("incoming_transfers", Json{{"transfer_type", "all"}, {"account_index", metadata.at("accountIndex")},
      {"subaddr_indices", Json::array({0})}});
  const auto owned = incoming.value("transfers", Json::array());
  require(!owned.empty() && owned.size() <= 10000, "native Monero source has no bounded owned output snapshot");
  const auto tenant = uuid(descriptor, "tenantId"), key = uuid(descriptor, "keyId"), session = uuid(descriptor, "sessionId");
  const auto aad = context(tenant, key, local_party) + descriptor.dump();
  Json draft = nullptr;
  if (self == 0) {
    const auto transferred = rpc.call("transfer", Json{{"destinations", Json::array({Json{{"address", policy.at("recipient")}, {"amount", atomic(policy.at("amountAtomic"))}}})},
        {"account_index", metadata.at("accountIndex")}, {"subaddr_indices", Json::array({0})}, {"priority", 1}, {"ring_size", 16},
        {"unlock_time", 0}, {"do_not_relay", true}, {"get_tx_key", false}, {"get_tx_hex", false}, {"get_tx_metadata", false}});
    draft = transferred.at("multisig_txset");
    store.put("monero-draft-" + session, aad, mem_t(draft.dump()));
  }
  const auto proposals = exchange(draft);
  require(proposals.size() == 2 && proposals.at(0).is_string() && proposals.at(1).is_null(), "native Monero proposal source differs");
  const auto proposal = proposals.at(0).get<std::string>();
  const auto original = review_payment(rpc, metadata, policy, proposal, owned);
  store.put("monero-reviewed-" + session, aad, mem_t(Json{{"draft", proposal}, {"description", original},
      {"owned", reviewed_owned_inputs(original, owned)}}.dump()));
  const auto reviewed = exchange(sha256(original.dump()));
  require(reviewed.size() == 2 && reviewed.at(0) == reviewed.at(1), "native Monero participants did not approve the same payment");
  Json selected = nullptr;
  if (self == 1) {
    const auto signed_tx = rpc.call("sign_multisig", Json{{"tx_data_hex", proposal}});
    require(signed_tx.at("tx_hash_list").size() == 1, "native Monero final hash count differs");
    selected = Json{{"txHash", signed_tx.at("tx_hash_list").at(0)}, {"transactionSetHex", signed_tx.at("tx_data_hex")}};
    rpc.call("store");
    store.put("monero-cosigned-" + session, aad, mem_t(selected.dump()));
  }
  const auto completed = exchange(selected);
  require(completed.size() == 2 && completed.at(0).is_null() && completed.at(1).is_object(), "native Monero final co-signer differs");
  selected = completed.at(1);
  const auto tx_hash = selected.at("txHash").get<std::string>();
  require(tx_hash.size() == 64, "native Monero final transaction hash is absent"); text(tx_hash);
  const auto final_description = review_payment(rpc, metadata, policy, selected.at("transactionSetHex"), owned);
  require(final_description.at("sources") == original.at("sources"), "native Monero co-signature changed the reviewed input set");
  require(final_description == original, "native Monero co-signature changed the reviewed payment");
  const auto artifact = payment_artifact(descriptor, policy, selected, original);
  store.put("monero-artifact-" + session, aad, mem_t(artifact.dump()));
  const auto agreement = exchange(sha256(artifact.dump()));
  require(agreement.size() == 2 && agreement.at(0) == agreement.at(1), "native Monero final artifacts disagree");
  // The opaque txset is encrypted by the official native wallet with its private view key.
  // Both participants publish the same artifact; Java must require two matching authenticated results.
  return Json{{"publicKeyHex", metadata.at("publicKeyHex")}, {"publicAddress", metadata.at("address")}, {"signatureHex", hex(artifact.dump())}};
}

std::optional<Json> monero_recovery_material(const Json& descriptor, const std::string& local_party, EncryptedStore& store) {
  require(descriptor.at("operation") == "SIGN" && descriptor.at("algorithm") == "MONERO_NATIVE_2_OF_3"
      && descriptor.at("epoch") == 1 && descriptor.at("online").size() == 2, "invalid native Monero original recovery request");
  const auto session = uuid(descriptor, "sessionId");
  const auto aad = context(uuid(descriptor, "tenantId"), uuid(descriptor, "keyId"), local_party);
  const auto request = store.get("request-" + session, aad);
  require(request && request->to_string() == descriptor.dump(), "native Monero recovery changed its original request");
  if (auto artifact = store.get("monero-artifact-" + session, aad + descriptor.dump()))
    return Json{{"artifact", Json::parse(artifact->to_string())}};
  // An original co-signer must retain its own wallet-RPC result; a peer cannot manufacture that local fact.
  if (descriptor.at("online").at(1) == local_party)
    if (auto selected = store.get("monero-cosigned-" + session, aad + descriptor.dump()))
      return Json{{"selected", Json::parse(selected->to_string())}};
  return std::nullopt;
}

Json recover_monero_payment(const Json& descriptor, const Json& configuration, const std::string& local_party,
    int self, EncryptedStore& store, const std::function<std::optional<Json>(const std::string&)>& fetch_cosigned) {
  require((self == 0 || self == 1) && descriptor.at("online").at(self) == local_party,
      "native Monero original recovery holder differs");
  const auto session = uuid(descriptor, "sessionId");
  const auto aad = context(uuid(descriptor, "tenantId"), uuid(descriptor, "keyId"), local_party) + descriptor.dump();
  const auto reviewed_record = store.get("monero-reviewed-" + session, aad);
  require(reviewed_record.has_value(), "native Monero original local review is unavailable");
  const auto reviewed = Json::parse(reviewed_record->to_string());
  auto material = monero_recovery_material(descriptor, local_party, store);
  if (!material && self == 0) material = fetch_cosigned(descriptor.at("online").at(1));
  require(material.has_value(), "native Monero original signed material is unavailable; signature remains indeterminate");
  const auto selected = material->contains("artifact")
      ? Json{{"txHash", material->at("artifact").at("transactionHash")}, {"transactionSetHex", material->at("artifact").at("transactionSetHex")}}
      : material->at("selected");
  const auto policy = Json::parse(text(descriptor.at("messageHex")));
  Rpc rpc(configuration, "walletRpcUrl", "walletCredentialFile", true);
  const auto metadata = open_address(descriptor, configuration, local_party, store, rpc);
  bound_policy(descriptor, metadata, policy);
  require(policy.at("kind") == "PAYMENT", "native Monero recovery is not an original payment");
  // Revalidate the exact transaction against this holder's sealed pre-sign ownership review.
  // Never refresh/export nonces, transfer, sign again, choose inputs, or broadcast here.
  const auto final_description = review_payment(rpc, metadata, policy, selected.at("transactionSetHex"), reviewed.at("owned"));
  require(final_description == reviewed.at("description"), "native Monero recovered transaction changed its original review");
  const auto artifact = payment_artifact(descriptor, policy, selected, final_description);
  if (material->contains("artifact")) require(material->at("artifact") == artifact, "native Monero recovered artifact changed its original identity");
  store.put("monero-artifact-" + session, aad, mem_t(artifact.dump()));
  return Json{{"publicKeyHex", metadata.at("publicKeyHex")}, {"publicAddress", metadata.at("address")}, {"signatureHex", hex(artifact.dump())}};
}

Json relay_monero_payment(const Json& descriptor, const Json& configuration, const std::string& local_party, EncryptedStore& store) {
  require(descriptor.at("operation") == "MONERO_RELAY" && descriptor.at("epoch") == 1
      && descriptor.at("online") == Json::array({local_party}), "invalid native Monero relay participant");
  const auto policy = Json::parse(text(descriptor.at("messageHex")));
  require(policy.at("kind") == "RELAY", "unsupported native Monero relay policy");
  Rpc rpc(configuration, "walletRpcUrl", "walletCredentialFile", true);
  const auto metadata = open_address(descriptor, configuration, local_party, store, rpc);
  bound_policy(descriptor, metadata, policy);
  const auto aad = context(uuid(descriptor, "tenantId"), uuid(descriptor, "keyId"), local_party);
  const auto session = uuid(policy, "signSessionId");
  const auto original = store.get("request-" + session, aad);
  auto completed = store.get("recovered-result-" + session, aad);
  if (!completed) completed = store.get("result-" + session, aad);
  require(original.has_value() && completed.has_value(), "native Monero completed signing receipt is unavailable");
  const auto request = Json::parse(original->to_string()), result = Json::parse(completed->to_string());
  require(request.at("operation") == "SIGN" && request.at("algorithm") == "MONERO_NATIVE_2_OF_3"
      && request.at("tenantId") == descriptor.at("tenantId") && request.at("keyId") == descriptor.at("keyId")
      && request.at("epoch") == descriptor.at("epoch") && result.at("state") == "COMPLETED"
      && result.at("publicKeyHex") == metadata.at("publicKeyHex")
      && result.at("intentHash") == hash_field(policy.at("signIntentHash"))
      && result.at("intentHash") == sha256(request.dump()), "native Monero original signing receipt binding differs");
  const auto stored = store.get("monero-artifact-" + session, aad + request.dump());
  require(stored.has_value(), "native Monero selected transaction artifact is unavailable");
  const auto artifact = Json::parse(stored->to_string());
  const auto tx_hash = hash_field(policy.at("transactionHash"));
  require(result.at("signatureHex") == hex(artifact.dump()) && artifact.at("transactionHash") == tx_hash
      && artifact.at("intentHash") == result.at("intentHash") && artifact.at("sessionId") == session
      && artifact.at("policy").at("sender") == metadata.at("address"), "native Monero relay differs from its selected original artifact");
  Rpc daemon(configuration, "daemonRpcUrl", "daemonCredentialFile", false);
  auto observed = transaction_state(daemon, tx_hash).facts;
  if (!observed.at("known").get<bool>()) {
    try {
      const auto submitted = rpc.call("submit_multisig", Json{{"tx_data_hex", artifact.at("transactionSetHex")}});
      require(submitted.at("tx_hash_list") == Json::array({tx_hash}), "native Monero relay returned a different hash");
    } catch (const MoneroError&) {
      // A rejected or lost relay response is not evidence of failure; query this exact hash.
    }
    observed = transaction_state(daemon, tx_hash).facts;
  }
  require(observed.at("known").get<bool>() && !observed.at("doubleSpendSeen").get<bool>(), "native Monero relay remains indeterminate");
  // Signature completion already required two parties. Broadcasting the immutable result needs one surviving holder.
  return Json{{"publicKeyHex", metadata.at("publicKeyHex")}, {"publicAddress", metadata.at("address")},
      {"monero", Json{{"kind", "RELAY"}, {"transactionHash", tx_hash}, {"signSessionId", session}, {"signIntentHash", policy.at("signIntentHash")}}}};
}

Json observe_monero_account(const Json& descriptor, const Json& configuration, const std::string& local_party,
                            EncryptedStore& store, const MoneroExchange& exchange) {
  require(descriptor.at("operation") == "MONERO_OBSERVE" && descriptor.at("epoch") == 1
      && descriptor.at("online").size() == 2, "invalid native Monero observation group");
  const auto policy = Json::parse(text(descriptor.at("messageHex")));
  require(policy.at("kind") == "SNAPSHOT", "unsupported native Monero observation policy");
  Rpc rpc(configuration, "walletRpcUrl", "walletCredentialFile", true);
  const auto metadata = open_address(descriptor, configuration, local_party, store, rpc);
  bound_policy(descriptor, metadata, policy);
  Rpc daemon(configuration, "daemonRpcUrl", "daemonCredentialFile", false);
  const auto height = integer(policy.at("height")), from = integer(policy.at("fromHeight"));
  require(from <= height && height - from <= 1000, "native Monero observation range exceeds its bound");
  const auto anchor = header(daemon, height);
  require(anchor.at("hash") == hash_field(policy.at("blockHash")), "native Monero observation anchor changed");
  rpc.call("refresh");
  const auto information = exchange(rpc.call("export_multisig_info").at("info"));
  rpc.call("import_multisig_info", Json{{"info", information}});
  const auto wallet_height = integer(rpc.call("get_height").at("height"));
  require(wallet_height > height && integer(daemon.call("get_info").at("height")) == wallet_height,
      "native Monero private wallet has not reached the current canonical view");
  const auto tip = header(daemon, wallet_height - 1);
  const auto balance = rpc.call("get_balance", Json{{"account_index", metadata.at("accountIndex")}, {"address_indices", Json::array({0})}, {"strict", true}});
  const auto incoming = rpc.call("incoming_transfers", Json{{"transfer_type", "all"}, {"account_index", metadata.at("accountIndex")}, {"subaddr_indices", Json::array({0})}});
  const auto owned = incoming.value("transfers", Json::array());
  require(owned.is_array() && owned.size() <= 10000, "native Monero output observation exceeds its bound");
  const auto history = rpc.call("get_transfers", Json{{"out", true}, {"all_accounts", true}, {"filter_by_height", true},
      {"min_height", from == 0 ? 0 : from - 1}, {"max_height", height}}).value("out", Json::array());
  require(history.is_array() && history.size() <= 10000, "native Monero outgoing observation exceeds its bound");
  std::map<std::string, std::string> senders;
  for (const auto& transfer : history) {
    require(transfer.at("type") == "out" && integer(transfer.at("subaddr_index").at("minor")) == 0,
        "native Monero outgoing account identity differs");
    const auto tx = hash_field(transfer.at("txid"));
    const auto sender = transfer.at("address").get<std::string>();
    require(senders.emplace(tx, sender).second || senders.at(tx) == sender, "native Monero outgoing transaction has ambiguous accounts");
  }
  Json outputs = Json::array();
  std::map<std::string, ObservedTransaction> transactions;
  for (const auto& output : owned) {
    const auto block_height = integer(output.at("block_height"));
    if (block_height < from || block_height > height) continue;
    if (integer(output.at("amount")) == 0) continue;
    require(output.at("subaddr_index").at("major") == metadata.at("accountIndex")
        && output.at("subaddr_index").at("minor") == 0, "native Monero observation escaped its registered account");
    const auto tx_hash = hash_field(output.at("tx_hash")), key = hash_field(output.at("pubkey"));
    if (!transactions.count(tx_hash)) transactions.emplace(tx_hash, transaction_state(daemon, tx_hash));
    const auto& evidence = transactions.at(tx_hash);
    require(evidence.facts.at("known").get<bool>() && !evidence.facts.at("inPool").get<bool>()
        && !evidence.facts.at("doubleSpendSeen").get<bool>() && integer(evidence.facts.at("blockHeight")) == block_height
        && integer(evidence.facts.at("confirmations")) == wallet_height - block_height,
        "native Monero owned output has no matching canonical transaction");
    std::optional<size_t> output_index;
    const auto& original_outputs = evidence.transaction.at("vout");
    for (size_t index = 0; index < original_outputs.size(); ++index) {
      const auto& target = original_outputs.at(index).at("target");
      const auto target_key = target.contains("tagged_key") ? target.at("tagged_key").at("key") : target.at("key");
      if (target_key != key) continue;
      require(!output_index.has_value() && integer(evidence.indices.at(index)) == integer(output.at("global_index")),
          "native Monero owned output key or global index differs from the original transaction");
      if (evidence.facts.at("coinbase").get<bool>())
        require(integer(original_outputs.at(index).at("amount")) == integer(output.at("amount")), "native Monero miner output amount differs");
      output_index = index;
    }
    require(output_index.has_value() && output.at("spent").is_boolean() && output.at("frozen").is_boolean()
        && output.at("unlocked").is_boolean(), "native Monero owned output identity or lock state is absent");
    outputs.push_back(Json{{"transactionHash", tx_hash}, {"outputKey", key}, {"outputIndex", *output_index},
        {"globalIndex", integer(output.at("global_index"))}, {"amountAtomic", std::to_string(integer(output.at("amount")))},
        {"blockHeight", block_height}, {"blockHash", evidence.facts.at("blockHash")},
        {"confirmations", evidence.facts.at("confirmations")}, {"coinbase", evidence.facts.at("coinbase")},
        {"unlockTime", evidence.facts.at("unlockTime")}, {"spent", output.at("spent")},
        {"frozen", output.at("frozen")}, {"unlocked", output.at("unlocked")},
        {"sourceAddress", senders.count(tx_hash) ? Json(senders.at(tx_hash)) : Json(nullptr)},
        {"change", senders.count(tx_hash) && senders.at(tx_hash) == metadata.at("address")}});
  }
  std::sort(outputs.begin(), outputs.end(), [](const auto& left, const auto& right) { return left.dump() < right.dump(); });
  Json snapshot{{"kind", "SNAPSHOT"}, {"network", metadata.at("network")}, {"genesisHash", metadata.at("genesisHash")},
      {"address", metadata.at("address")}, {"accountIndex", metadata.at("accountIndex")}, {"anchorHeight", height},
      {"anchorHash", anchor.at("hash")}, {"viewHeight", wallet_height - 1}, {"viewHash", tip.at("hash")},
      {"balanceAtomic", std::to_string(integer(balance.at("balance")))},
      {"unlockedBalanceAtomic", std::to_string(integer(balance.at("unlocked_balance")))}, {"outputs", outputs}};
  if (policy.contains("transactionHash")) snapshot["transaction"] = transaction_state(daemon, hash_field(policy.at("transactionHash"))).facts;
  require(integer(rpc.call("get_height").at("height")) == wallet_height
      && integer(daemon.call("get_info").at("height")) == wallet_height
      && header(daemon, wallet_height - 1).at("hash") == tip.at("hash")
      && header(daemon, height).at("hash") == anchor.at("hash"), "native Monero canonical view changed during observation");
  require(snapshot.dump().size() <= 400 * 1024, "native Monero observation result exceeds its publication bound");
  const auto agreed = exchange(sha256(snapshot.dump()));
  require(agreed.size() == 2 && agreed.at(0) == agreed.at(1), "native Monero observation participants disagree");
  return Json{{"publicKeyHex", metadata.at("publicKeyHex")}, {"publicAddress", metadata.at("address")}, {"monero", snapshot}};
}
}
