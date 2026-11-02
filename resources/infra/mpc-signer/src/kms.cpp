#include <wallet_mpc/kms.h>

#include <httplib.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <array>
#include <regex>
#include <stdexcept>

namespace wallet_mpc {
namespace {
using Json = nlohmann::json;
using coinbase::buf_t;
using coinbase::mem_t;
struct TransportError : std::runtime_error { using std::runtime_error::runtime_error; };
void require(bool valid, const char* message) { if (!valid) throw std::runtime_error(message); }
struct Wipe {
  std::string& value;
  ~Wipe() { if (!value.empty()) OPENSSL_cleanse(value.data(), value.size()); }
};
struct WipeBytes {
  buf_t& value;
  ~WipeBytes() { value.secure_bzero(); }
};
struct WipeJsonField {
  Json& value; const char* name;
  ~WipeJsonField() {
    if (value.is_object() && value.contains(name) && value.at(name).is_string()) {
      auto& text = value.at(name).get_ref<std::string&>();
      if (!text.empty()) OPENSSL_cleanse(text.data(), text.size());
    }
  }
};
std::string base64(mem_t bytes) {
  std::string result(static_cast<size_t>(4 * ((bytes.size + 2) / 3)), '\0');
  // EVP_EncodeBlock appends a NUL, so provide its extra byte explicitly.
  result.resize(result.size() + 1);
  const int size = EVP_EncodeBlock(reinterpret_cast<unsigned char*>(result.data()), bytes.data, bytes.size);
  require(size >= 0, "cannot encode KMS request");
  result.resize(static_cast<size_t>(size));
  return result;
}
buf_t decode(const std::string& text, size_t limit) {
  require(!text.empty() && text.size() % 4 == 0 && text.size() <= limit * 2, "invalid KMS base64 size");
  require(std::regex_match(text, std::regex("[A-Za-z0-9+/]*={0,2}")), "invalid KMS base64 encoding");
  buf_t bytes(static_cast<int>(text.size() / 4 * 3));
  const int size = EVP_DecodeBlock(bytes.data(), reinterpret_cast<const unsigned char*>(text.data()), static_cast<int>(text.size()));
  if (size < 0) { bytes.secure_bzero(); throw std::runtime_error("invalid KMS base64 encoding"); }
  const int padding = (text.back() == '=') + (text.size() > 1 && text[text.size() - 2] == '=');
  bytes.resize(size - padding);
  auto canonical = base64(bytes);
  Wipe canonical_guard{canonical};
  if (static_cast<size_t>(bytes.size()) > limit || canonical != text) {
    bytes.secure_bzero(); throw std::runtime_error("invalid KMS base64 encoding");
  }
  return bytes;
}
std::string metadata_access_token() {
  httplib::Client client("169.254.169.254", 80);
  client.set_connection_timeout(2); client.set_read_timeout(5); client.set_write_timeout(5);
  client.set_follow_location(false); client.set_payload_max_length(16384);
  auto response = client.Get("/computeMetadata/v1/instance/service-accounts/default/token",
                             httplib::Headers{{"Metadata-Flavor", "Google"}});
  require(response && response->status == 200 && response->get_header_value("Metadata-Flavor") == "Google",
          "cannot obtain attached GCP identity");
  Wipe body{response->body};
  auto parsed = Json::parse(response->body);
  WipeJsonField token_guard{parsed, "access_token"};
  require(parsed.at("token_type") == "Bearer" && parsed.at("expires_in").get<int>() > 30,
          "invalid GCP identity response");
  const auto& token = parsed.at("access_token").get_ref<const std::string&>();
  require(!token.empty() && token.size() < 8192 && token.find_first_of("\r\n") == std::string::npos,
          "invalid GCP identity token");
  return token;
}
}  // namespace

uint32_t crc32c(mem_t bytes) {
  uint32_t crc = 0xffffffff;
  for (int i = 0; i < bytes.size; ++i) {
    crc ^= bytes[i];
    for (int bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0x82f63b78 & (0u - (crc & 1)));
  }
  return ~crc;
}

StorageKey unwrap_storage_key(const Json& config, const std::string& party, const KmsDecrypt& decrypt) {
  const char* stage = "configuration";
  try {
    require(!config.contains("encryptionKeyFile") && !config.contains("testStorageKeyHex"),
            "plaintext storage key configuration is forbidden");
    require(std::regex_match(party, std::regex("[0-9a-f]{64}")), "invalid KMS holder identity");
    const auto& kms = config.at("storageKey");
    const auto key_name = kms.at("kmsKeyName").get<std::string>();
    require(std::regex_match(key_name, std::regex("projects/[a-z0-9-]+/locations/[a-z0-9-]+/keyRings/[A-Za-z0-9_-]+/cryptoKeys/[A-Za-z0-9_-]+")),
            "invalid KMS key resource");
    const auto ciphertext = decode(kms.at("wrappedKey").get<std::string>(), 8192);
    const auto aad = "SW-MPC-STORAGE-KEY-V1\n" + party;
    const auto request = Json{{"ciphertext", base64(ciphertext)}, {"ciphertextCrc32c", std::to_string(crc32c(ciphertext))},
                              {"additionalAuthenticatedData", base64(mem_t(aad))},
                              {"additionalAuthenticatedDataCrc32c", std::to_string(crc32c(mem_t(aad)))}}.dump();
    stage = "KMS HTTPS decrypt";
    auto body = decrypt(key_name, request);
    Wipe body_guard{body};
    require(body.size() <= 16384, "KMS response exceeds limit");
    stage = "response JSON";
    auto response = Json::parse(body);
    WipeJsonField plaintext_guard{response, "plaintext"};
    stage = "plaintext decoding";
    auto decoded = decode(response.at("plaintext").get_ref<const std::string&>(), 32);
    WipeBytes decoded_guard{decoded};
    require(decoded.size() == 32, "KMS storage key must be 32 bytes");
    stage = "plaintext CRC32C";
    const auto checksum = response.at("plaintextCrc32c").get<std::string>();
    require(checksum == std::to_string(crc32c(decoded)), "KMS storage key integrity check failed");
    stage = "locked memory";
    return StorageKey(decoded);
  } catch (const TransportError&) {
    throw;  // Only fixed HTTP status / library error identifiers, never a body.
  } catch (const std::exception&) {
    // JSON/type/HTTP errors must not disclose an encoded key, token or response.
    throw std::runtime_error(std::string("KMS storage key loading failed at ") + stage);
  }
}

std::string google_kms_decrypt(const Json& config, const std::string& key_name,
                              const std::string& request, const std::string& access_token) {
  httplib::SSLClient client("cloudkms.googleapis.com", 443);
#ifdef WALLET_MPC_TEST_HTTPS_PROXY
  // Local management-machine acceptance only. Not compiled into installed nodes.
  if (config.contains("testHttpsProxyPort")) {
    const auto port = config.at("testHttpsProxyPort").get<int>();
    require(port > 0 && port <= 65535, "invalid local test proxy");
    client.set_proxy("127.0.0.1", port);
  }
#endif
  const auto ca = config.at("storageKey").at("caBundleFile").get<std::string>();
  require(!ca.empty(), "KMS HTTPS CA bundle is required");
  client.set_ca_cert_path(ca.c_str());
  client.enable_server_certificate_verification(true);
  client.enable_server_hostname_verification(true);
  client.set_follow_location(false);
  client.set_connection_timeout(5); client.set_read_timeout(10); client.set_write_timeout(5);
  client.set_payload_max_length(16384);
  auto authorization = "Bearer " + access_token;
  Wipe auth_guard{authorization};
  httplib::Headers headers{{"Authorization", authorization}};
  auto response = client.Post("/v1/" + key_name + ":decrypt", headers, request, "application/json");
  // httplib copies request headers internally; keep them transient, never log them.
  auto& header = headers.find("Authorization")->second;
  OPENSSL_cleanse(header.data(), header.size());
  if (!response) throw TransportError("GCP KMS transport failed: " + httplib::to_string(response.error()));
  if (response->status != 200) throw TransportError("GCP KMS decrypt HTTP " + std::to_string(response->status));
  return std::move(response->body);
}

StorageKey load_storage_key(const Json& config, const std::string& party) {
  return unwrap_storage_key(config, party, [&](const std::string& name, const std::string& request) {
    auto token = metadata_access_token();
    Wipe token_guard{token};
    return google_kms_decrypt(config, name, request, token);
  });
}
}  // namespace wallet_mpc
