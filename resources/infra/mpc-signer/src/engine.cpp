#include <wallet_mpc/engine.h>

#include <algorithm>
#include <set>
#include <stdexcept>
#include <utility>

#include <cbmpc/api/ecdsa_mp.h>
#include <cbmpc/api/eddsa_mp.h>
#include <cbmpc/api/schnorr_mp.h>
#include <cbmpc/core/access_structure.h>

namespace wallet_mpc {
namespace {
using namespace coinbase;
using namespace coinbase::api;

void checked(error_t result) {
  if (result != SUCCESS) {
    // Never include protocol messages or secret blobs in an exception.
    throw std::runtime_error("cb-mpc operation failed: " + std::to_string(result));
  }
}

access_structure_t access_structure(const Parties& parties) {
  std::set<std::string> unique;
  for (const auto& party : parties) {
    if (party.empty() || !unique.insert(party).second) {
      throw std::invalid_argument("three distinct, stable party identities are required");
    }
  }
  return access_structure_t::Threshold(2, {access_structure_t::leaf(parties[0]),
                                          access_structure_t::leaf(parties[1]),
                                          access_structure_t::leaf(parties[2])});
}

job_mp_t job(const std::vector<std::string>& names, int self, data_transport_i& transport) {
  if (self < 0 || static_cast<size_t>(self) >= names.size()) {
    throw std::invalid_argument("local party index is outside the execution group");
  }
  std::vector<std::string_view> views;
  for (const auto& name : names) views.emplace_back(name);
  return job_mp_t{self, std::move(views), transport};
}
}  // namespace

KeyShare::KeyShare(Algorithm selected_algorithm, Parties selected_parties, coinbase::buf_t selected_blob)
    : algorithm(selected_algorithm), parties(std::move(selected_parties)), blob(std::move(selected_blob)) {}

KeyShare generate(Algorithm algorithm, const Parties& parties, int self, data_transport_i& transport) {
  auto ac = access_structure(parties);
  std::vector<std::string> names(parties.begin(), parties.end());
  auto execution = job(names, self, transport);
  const std::vector<std::string_view> contributors{parties[0], parties[1]};
  buf_t blob, sid;
  switch (algorithm) {
    case Algorithm::EcdsaSecp256k1:
      checked(ecdsa_mp::dkg_ac(execution, curve_id::secp256k1, sid, ac, contributors, blob));
      break;
    case Algorithm::Ed25519:
      checked(eddsa_mp::dkg_ac(execution, curve_id::ed25519, sid, ac, contributors, blob));
      break;
    case Algorithm::SchnorrBip340:
      checked(schnorr_mp::dkg_ac(execution, curve_id::secp256k1, sid, ac, contributors, blob));
      break;
    default:
      throw std::invalid_argument("unsupported signing algorithm");
  }
  return KeyShare(algorithm, parties, std::move(blob));
}

KeyShare refresh(const KeyShare& key, int self, data_transport_i& transport) {
  auto ac = access_structure(key.parties);
  std::vector<std::string> names(key.parties.begin(), key.parties.end());
  auto execution = job(names, self, transport);
  const std::vector<std::string_view> contributors{key.parties[0], key.parties[1]};
  buf_t blob, sid;
  switch (key.algorithm) {
    case Algorithm::EcdsaSecp256k1:
      checked(ecdsa_mp::refresh_ac(execution, sid, key.blob, ac, contributors, blob));
      break;
    case Algorithm::Ed25519:
      checked(eddsa_mp::refresh_ac(execution, sid, key.blob, ac, contributors, blob));
      break;
    case Algorithm::SchnorrBip340:
      checked(schnorr_mp::refresh_ac(execution, sid, key.blob, ac, contributors, blob));
      break;
    default:
      throw std::invalid_argument("unsupported signing algorithm");
  }
  return KeyShare(key.algorithm, key.parties, std::move(blob));
}

buf_t public_key(const KeyShare& key) {
  buf_t result;
  switch (key.algorithm) {
    case Algorithm::EcdsaSecp256k1:
      checked(ecdsa_mp::get_public_key_compressed(key.blob, result));
      break;
    case Algorithm::Ed25519:
      checked(eddsa_mp::get_public_key_compressed(key.blob, result));
      break;
    case Algorithm::SchnorrBip340:
      checked(schnorr_mp::extract_public_key_xonly(key.blob, result));
      break;
    default:
      throw std::invalid_argument("unsupported signing algorithm");
  }
  return result;
}

buf_t sign(const KeyShare& key, const std::vector<std::string>& online_parties, int self,
           mem_t message, data_transport_i& transport) {
  auto ac = access_structure(key.parties);
  if (online_parties.size() != 2 || online_parties[0] == online_parties[1]) {
    throw std::invalid_argument("signing requires two distinct registered parties");
  }
  for (const auto& party : online_parties) {
    if (std::find(key.parties.begin(), key.parties.end(), party) == key.parties.end()) {
      throw std::invalid_argument("signing party is not registered for this key");
    }
  }
  if (message.size < 0 || (message.size > 0 && !message.data)) {
    throw std::invalid_argument("invalid signing message");
  }
  if (key.algorithm != Algorithm::Ed25519 && message.size != 32) {
    throw std::invalid_argument("ECDSA and BIP340 require a 32-byte digest");
  }
  auto execution = job(online_parties, self, transport);
  buf_t result;
  switch (key.algorithm) {
    case Algorithm::EcdsaSecp256k1:
      checked(ecdsa_mp::sign_ac(execution, key.blob, ac, message, 0, result));
      break;
    case Algorithm::Ed25519:
      checked(eddsa_mp::sign_ac(execution, key.blob, ac, message, 0, result));
      break;
    case Algorithm::SchnorrBip340:
      checked(schnorr_mp::sign_ac(execution, key.blob, ac, message, 0, result));
      break;
    default:
      throw std::invalid_argument("unsupported signing algorithm");
  }
  return result;
}
}  // namespace wallet_mpc
