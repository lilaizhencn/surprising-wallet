#include <wallet_mpc/authorization.h>

#include <openssl/evp.h>
#include <openssl/sha.h>
#include <algorithm>
#include <memory>
#include <regex>
#include <stdexcept>

namespace wallet_mpc {
namespace {
using Json = nlohmann::json;
void require(bool value, const char* message) { if (!value) throw std::invalid_argument(message); }
std::string decode(const std::string& value, size_t max) {
  require(value.size() % 2 == 0 && value.size() <= max * 2, "invalid authorization hex size");
  auto digit = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    throw std::invalid_argument("invalid authorization hex");
  };
  std::string result(value.size() / 2, '\0');
  for (size_t i = 0; i < result.size(); ++i) result[i] = static_cast<char>(digit(value[i*2])*16 + digit(value[i*2+1]));
  return result;
}
std::string digest(const std::string& value) {
  unsigned char bytes[32];
  require(SHA256(reinterpret_cast<const unsigned char*>(value.data()), value.size(), bytes), "authorization hash failed");
  const char* digits = "0123456789abcdef";
  std::string result;
  for (auto byte : bytes) { result += digits[byte >> 4]; result += digits[byte & 15]; }
  return result;
}
bool uuid(const std::string& value) {
  return std::regex_match(value, std::regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
}
}

Json verify_authorization(const Json& proof, const Json& descriptor,
    const std::vector<std::string>& trusted_public_keys, int64_t now, bool require_fresh) {
  require(proof.is_object() && proof.size() == 2 && !trusted_public_keys.empty(), "independent signing authorization required");
  const auto payload = decode(proof.at("payloadHex").get<std::string>(), 4096);
  const auto signature = decode(proof.at("signatureHex").get<std::string>(), 64);
  require(signature.size() == 64, "authorization signature size differs");
  const std::string signed_bytes = "SW-MPC-AUTHORIZATION-V1\n" + payload;
  bool verified = false;
  for (const auto& encoded_key : trusted_public_keys) {
    const auto raw = decode(encoded_key, 32);
    require(raw.size() == 32, "authorization trust key size differs");
    std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)> key(EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, nullptr,
        reinterpret_cast<const unsigned char*>(raw.data()), raw.size()), EVP_PKEY_free);
    std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> context(EVP_MD_CTX_new(), EVP_MD_CTX_free);
    require(key && context, "authorization verifier allocation failed");
    if (EVP_DigestVerifyInit(context.get(), nullptr, nullptr, nullptr, key.get()) == 1 &&
        EVP_DigestVerify(context.get(), reinterpret_cast<const unsigned char*>(signature.data()), signature.size(),
            reinterpret_cast<const unsigned char*>(signed_bytes.data()), signed_bytes.size()) == 1) { verified = true; break; }
  }
  require(verified, "independent authorization signature differs");
  const auto claims = Json::parse(payload);
  require(claims.is_object() && claims.size() == 17 && claims.at("v") == 1 && claims.dump() == payload,
      "authorization claims are not canonical");
  for (auto field : {"authorizationId", "requestId", "tenantId", "keyId", "sessionId"})
    require(uuid(claims.at(field).get<std::string>()), "authorization UUID differs");
  require(claims.at("operation") == "SIGN" || claims.at("operation") == "MONERO_RELAY", "authorization operation differs");
  for (auto field : {"tenantId", "keyId", "epoch", "sessionId", "operation", "algorithm", "online"})
    require(claims.at(field) == descriptor.at(field), "authorization execution scope differs");
  require(std::regex_match(claims.at("chain").get<std::string>(), std::regex("[A-Z][A-Z0-9_]{0,31}")) &&
      std::regex_match(claims.at("network").get<std::string>(), std::regex("[a-zA-Z0-9_.:-]{1,128}")) &&
      std::regex_match(claims.at("businessHash").get<std::string>(), std::regex("[0-9a-f]{64}")), "authorization business scope differs");
  const std::vector<std::string> purposes{"WITHDRAWAL", "COLLECTION", "RECOVERY", "FUNDING", "ADDRESS_PREPARATION", "DEV_FAUCET"};
  require(std::find(purposes.begin(), purposes.end(), claims.at("purpose").get<std::string>()) != purposes.end(), "authorization purpose differs");
  require(claims.at("messageHash") == digest(decode(descriptor.at("messageHex").get<std::string>(), 1024*1024)),
      "authorized message differs");
  require(claims.at("issuedAt").is_number_integer() && claims.at("expiresAt").is_number_integer(), "authorization time differs");
  const auto issued = claims.at("issuedAt").get<int64_t>(), expires = claims.at("expiresAt").get<int64_t>();
  require(issued >= 0 && issued <= INT64_MAX - 300 && expires > issued && expires <= issued + 300, "authorization lifetime differs");
  if (require_fresh) require(now >= issued && now < expires, "authorization is outside its validity period");
  return claims;
}
}
