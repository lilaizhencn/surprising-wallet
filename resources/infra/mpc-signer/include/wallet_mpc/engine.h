#pragma once

#include <array>
#include <string>
#include <vector>

#include <cbmpc/core/job.h>

namespace wallet_mpc {

enum class Algorithm { EcdsaSecp256k1, Ed25519, SchnorrBip340 };
using Parties = std::array<std::string, 3>;

// One node's share only. The transport and returned share must remain inside
// that node's trust boundary; this type must never be a wallet API response.
struct KeyShare {
  Algorithm algorithm;
  Parties parties;
  coinbase::buf_t blob;

  KeyShare(Algorithm algorithm, Parties parties, coinbase::buf_t blob);
  KeyShare(KeyShare&&) = default;
  KeyShare& operator=(KeyShare&&) = default;
  KeyShare(const KeyShare&) = delete;
  KeyShare& operator=(const KeyShare&) = delete;
};

// Each call is synchronous and belongs to one local protocol execution. The
// caller owns authentication, authorization, encrypted storage, and scheduling.
KeyShare generate(Algorithm algorithm, const Parties& parties, int self,
                  coinbase::api::data_transport_i& transport);
KeyShare refresh(const KeyShare& key, int self, coinbase::api::data_transport_i& transport);
coinbase::buf_t public_key(const KeyShare& key);
coinbase::buf_t sign(const KeyShare& key, const std::vector<std::string>& online_parties, int self,
                     coinbase::mem_t message, coinbase::api::data_transport_i& transport);

}  // namespace wallet_mpc
