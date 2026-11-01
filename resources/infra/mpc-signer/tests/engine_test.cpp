#include <wallet_mpc/engine.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <exception>
#include <iostream>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <thread>

#include <openssl/bn.h>
#include <openssl/ec.h>
#include <openssl/ecdsa.h>
#include <openssl/evp.h>
#include <openssl/obj_mac.h>
#include <openssl/sha.h>

namespace {
using namespace coinbase;
using namespace wallet_mpc;

void require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

// Unit-test-only transport: executes real cb-mpc protocols without a network.
// The deployable service must supply mutually authenticated encrypted channels.
struct Channel {
  std::mutex mutex;
  std::condition_variable ready;
  std::deque<buf_t> messages;
};

struct TestNetwork {
  int count;
  std::vector<std::unique_ptr<Channel>> channels;
  std::atomic<bool> aborted{false};
  explicit TestNetwork(int n) : count(n) {
    for (int i = 0; i < n * n; ++i) channels.push_back(std::make_unique<Channel>());
  }
  Channel& channel(int from, int to) { return *channels.at(from * count + to); }
  void abort() {
    aborted = true;
    for (auto& ch : channels) ch->ready.notify_all();
  }
};

class TestTransport final : public api::data_transport_i {
 public:
  TestTransport(TestNetwork& network, int self) : network_(network), self_(self) {}
  error_t send(api::party_idx_t receiver, mem_t message) override {
    if (network_.aborted) return E_NET_GENERAL;
    if (receiver < 0 || receiver >= network_.count || receiver == self_) return E_BADARG;
    auto& ch = network_.channel(self_, receiver);
    {
      std::lock_guard<std::mutex> lock(ch.mutex);
      ch.messages.emplace_back(message);
    }
    ch.ready.notify_one();
    return SUCCESS;
  }
  error_t receive(api::party_idx_t sender, buf_t& message) override {
    if (sender < 0 || sender >= network_.count || sender == self_) return E_BADARG;
    auto& ch = network_.channel(sender, self_);
    std::unique_lock<std::mutex> lock(ch.mutex);
    const bool available = ch.ready.wait_for(lock, std::chrono::seconds(30), [&] {
      return network_.aborted.load() || !ch.messages.empty();
    });
    if (!available || network_.aborted) return E_NET_GENERAL;
    message = std::move(ch.messages.front());
    ch.messages.pop_front();
    return SUCCESS;
  }
  error_t receive_all(const std::vector<api::party_idx_t>& senders, std::vector<buf_t>& messages) override {
    messages.resize(senders.size());
    for (size_t i = 0; i < senders.size(); ++i) {
      const auto status = receive(senders[i], messages[i]);
      if (status != SUCCESS) return status;
    }
    return SUCCESS;
  }
 private:
  TestNetwork& network_;
  int self_;
};

template <typename F>
void execute(int count, F operation) {
  TestNetwork network(count);
  std::vector<std::exception_ptr> errors(count);
  std::vector<std::thread> threads;
  for (int i = 0; i < count; ++i) {
    threads.emplace_back([&, i] {
      try {
        TestTransport transport(network, i);
        operation(i, transport);
      } catch (...) {
        errors[i] = std::current_exception();
        network.abort();
      }
    });
  }
  for (auto& thread : threads) thread.join();
  for (const auto& error : errors) if (error) std::rethrow_exception(error);
}

template <typename T, void (*Free)(T*)>
using Ossl = std::unique_ptr<T, decltype(Free)>;

bool verify_bip340(mem_t pub, mem_t message, mem_t signature) {
  if (pub.size != 32 || message.size != 32 || signature.size != 64) return false;
  Ossl<BN_CTX, BN_CTX_free> context(BN_CTX_new(), BN_CTX_free);
  Ossl<EC_GROUP, EC_GROUP_free> group(EC_GROUP_new_by_curve_name(NID_secp256k1), EC_GROUP_free);
  if (!context || !group) return false;
  Ossl<EC_POINT, EC_POINT_free> point(EC_POINT_new(group.get()), EC_POINT_free);
  Ossl<EC_POINT, EC_POINT_free> reconstructed(EC_POINT_new(group.get()), EC_POINT_free);
  Ossl<BIGNUM, BN_free> r(BN_bin2bn(signature.data, 32, nullptr), BN_free);
  Ossl<BIGNUM, BN_free> s(BN_bin2bn(signature.data + 32, 32, nullptr), BN_free);
  Ossl<BIGNUM, BN_free> p(BN_new(), BN_free), order(BN_new(), BN_free);
  Ossl<BIGNUM, BN_free> x(BN_new(), BN_free), y(BN_new(), BN_free), negative_e(BN_new(), BN_free);
  uint8_t compressed[33] = {2};
  std::copy(pub.data, pub.data + 32, compressed + 1);
  if (!point || !reconstructed || !r || !s || !p || !order || !x || !y || !negative_e) return false;
  if (EC_GROUP_get_curve(group.get(), p.get(), nullptr, nullptr, context.get()) != 1 ||
      EC_GROUP_get_order(group.get(), order.get(), context.get()) != 1 ||
      BN_cmp(r.get(), p.get()) >= 0 || BN_cmp(s.get(), order.get()) >= 0 ||
      EC_POINT_oct2point(group.get(), point.get(), compressed, sizeof(compressed), context.get()) != 1) return false;
  // Independent verifier using the BIP340 equation R = s*G - e*P.
  const std::string tag = "BIP0340/challenge";
  uint8_t tag_hash[32], hash[32], preimage[160];
  SHA256(reinterpret_cast<const uint8_t*>(tag.data()), tag.size(), tag_hash);
  std::copy(tag_hash, tag_hash + 32, preimage);
  std::copy(tag_hash, tag_hash + 32, preimage + 32);
  std::copy(signature.data, signature.data + 32, preimage + 64);
  std::copy(pub.data, pub.data + 32, preimage + 96);
  std::copy(message.data, message.data + 32, preimage + 128);
  SHA256(preimage, sizeof(preimage), hash);
  Ossl<BIGNUM, BN_free> e(BN_bin2bn(hash, sizeof(hash), nullptr), BN_free);
  if (!e || BN_nnmod(e.get(), e.get(), order.get(), context.get()) != 1 ||
      BN_mod_sub(negative_e.get(), order.get(), e.get(), order.get(), context.get()) != 1 ||
      EC_POINT_mul(group.get(), reconstructed.get(), s.get(), point.get(), negative_e.get(), context.get()) != 1 ||
      EC_POINT_is_at_infinity(group.get(), reconstructed.get()) != 0 ||
      EC_POINT_get_affine_coordinates(group.get(), reconstructed.get(), x.get(), y.get(), context.get()) != 1) return false;
  return !BN_is_odd(y.get()) && BN_cmp(x.get(), r.get()) == 0;
}

bool verify(Algorithm algorithm, mem_t pub, mem_t message, mem_t signature) {
  if (algorithm == Algorithm::SchnorrBip340) return verify_bip340(pub, message, signature);
  if (algorithm == Algorithm::Ed25519) {
    Ossl<EVP_PKEY, EVP_PKEY_free> key(EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, nullptr, pub.data, pub.size), EVP_PKEY_free);
    Ossl<EVP_MD_CTX, EVP_MD_CTX_free> context(EVP_MD_CTX_new(), EVP_MD_CTX_free);
    return key && context && EVP_DigestVerifyInit(context.get(), nullptr, nullptr, nullptr, key.get()) == 1 &&
           EVP_DigestVerify(context.get(), signature.data, signature.size, message.data, message.size) == 1;
  }
  Ossl<EC_KEY, EC_KEY_free> key(EC_KEY_new_by_curve_name(NID_secp256k1), EC_KEY_free);
  if (!key) return false;
  const uint8_t* pointer = pub.data;
  EC_KEY* raw = key.get();
  if (!o2i_ECPublicKey(&raw, &pointer, pub.size)) return false;
  return ECDSA_verify(0, message.data, message.size, signature.data, signature.size, key.get()) == 1;
}

template <typename F>
void rejects(F operation) {
  try { operation(); } catch (const std::invalid_argument&) { return; }
  throw std::runtime_error("invalid input was accepted");
}

void exercise(Algorithm algorithm, const char* label) {
  const Parties parties{
      "urn:uuid:2169bb78-5a25-44ef-a8c1-226fe6a645af",
      "urn:uuid:7456e5e2-d5ef-41b6-b76a-39ad8d2678b6",
      "urn:uuid:958660dc-8e46-4a50-9067-14b9fde9dc8b"};
  std::array<std::optional<KeyShare>, 3> shares;
  execute(3, [&](int i, TestTransport& transport) { shares[i].emplace(generate(algorithm, parties, i, transport)); });
  auto pub = public_key(*shares[0]);
  for (int i = 1; i < 3; ++i) require(public_key(*shares[i]) == pub, "DKG public keys differ");
  buf_t message(algorithm == Algorithm::Ed25519 ? 73 : 32);
  for (int i = 0; i < message.size(); ++i) message[i] = static_cast<uint8_t>(i);

  auto check_quorums = [&] {
    for (const auto& pair : {std::array<int, 2>{0, 1}, {0, 2}, {1, 2}}) {
      const std::vector<std::string> online{parties[pair[0]], parties[pair[1]]};
      std::array<buf_t, 2> signatures;
      execute(2, [&](int i, TestTransport& transport) {
        signatures[i] = sign(*shares[pair[i]], online, i, message, transport);
      });
      require(!signatures[0].empty() && signatures[1].empty(), "signature receiver semantics differ");
      require(verify(algorithm, pub, message, signatures[0]), "signature failed independent verification");
      buf_t changed(message);
      changed[0] ^= 1;
      require(!verify(algorithm, pub, changed, signatures[0]), "signature accepted a different message");
    }
  };
  check_quorums();
  std::array<std::optional<KeyShare>, 3> refreshed;
  execute(3, [&](int i, TestTransport& transport) { refreshed[i].emplace(refresh(*shares[i], i, transport)); });
  for (int i = 0; i < 3; ++i) {
    require(public_key(*refreshed[i]) == pub, "refresh changed the public key");
    require(refreshed[i]->blob != shares[i]->blob, "refresh reused the old share");
  }
  shares = std::move(refreshed);
  check_quorums();

  TestNetwork unused_network(2);
  TestTransport unused_transport(unused_network, 0);
  rejects([&] { sign(*shares[0], {parties[0]}, 0, message, unused_transport); });
  rejects([&] { sign(*shares[0], {parties[0], parties[0]}, 0, message, unused_transport); });
  rejects([&] { sign(*shares[0], {parties[0], "unknown-party"}, 0, message, unused_transport); });
  rejects([&] { sign(*shares[0], {parties[0], parties[1]}, 2, message, unused_transport); });
  rejects([&] { generate(algorithm, {parties[0], parties[0], parties[2]}, 0, unused_transport); });
  if (algorithm != Algorithm::Ed25519) {
    rejects([&] { sign(*shares[0], {parties[0], parties[1]}, 0, mem_t("short"), unused_transport); });
  }
  std::cout << label << ": DKG, AB/AC/BC signatures, refresh, verification and input rejection passed\n";
}
}  // namespace

int main() {
  try {
    exercise(Algorithm::EcdsaSecp256k1, "ECDSA-secp256k1");
    exercise(Algorithm::Ed25519, "Ed25519");
    exercise(Algorithm::SchnorrBip340, "BIP340");
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "MPC engine test failed: " << error.what() << '\n';
    return 1;
  }
}
