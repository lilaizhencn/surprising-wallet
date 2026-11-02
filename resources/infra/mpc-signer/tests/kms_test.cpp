#include <wallet_mpc/kms.h>
#include <wallet_mpc/store.h>
#include <openssl/crypto.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <unistd.h>

namespace {
using Json = nlohmann::json;
using coinbase::mem_t;
using namespace wallet_mpc;
void require(bool valid, const char* message) { if (!valid) throw std::runtime_error(message); }
template <typename F> void rejects(F call) {
  try { call(); } catch (const std::runtime_error& error) {
    require(std::string(error.what()).find("BwcH") == std::string::npos, "error leaked key material");
    return;
  }
  throw std::runtime_error("unsafe KMS response was accepted");
}
void round_trip(const Json& config, const std::string& party, const KmsDecrypt& decrypt) {
  char pattern[] = "/tmp/sw-kms-store-XXXXXX";
  const auto directory = mkdtemp(pattern);
  require(directory != nullptr, "cannot create temporary store");
  try {
    {
      EncryptedStore store(directory, unwrap_storage_key(config, party, decrypt));
      store.put("kms-record", "same-holder", mem_t("synthetic-protocol-record"));
    }
    {
      EncryptedStore restarted(directory, unwrap_storage_key(config, party, decrypt));
      require(*restarted.get("kms-record", "same-holder") == mem_t("synthetic-protocol-record"),
              "KMS key did not recover original encrypted record after restart");
    }
    for (const auto& path : std::filesystem::directory_iterator(directory))
      require(path.path().filename() == "kms-record", "key material was written to disk");
  } catch (...) { std::filesystem::remove_all(directory); throw; }
  std::filesystem::remove_all(directory);
}
void isolated() {
  require(crc32c(mem_t("123456789")) == 0xe3069283, "CRC32C reference vector failed");
  const std::string party(64, 'a');
  const Json config{{"storageKey", {{"kmsKeyName", "projects/test-project/locations/us-central1/keyRings/test/cryptoKeys/holder-1"},
                                    {"wrappedKey", "AQIDBA=="}}}};
  const std::string key(32, '\x07');
  Json reply{{"plaintext", "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="},
             {"plaintextCrc32c", std::to_string(crc32c(mem_t(key)))}};
  int calls = 0;
  auto transport = [&](const std::string& name, const std::string& body) {
    ++calls;
    require(name == config.at("storageKey").at("kmsKeyName"), "wrong KMS resource");
    const auto request = Json::parse(body);
    require(request.at("ciphertext") == "AQIDBA==" && request.contains("ciphertextCrc32c") &&
            request.contains("additionalAuthenticatedDataCrc32c"), "missing KMS integrity fields");
    require(request.at("additionalAuthenticatedData").get<std::string>().size() > 64, "missing holder AAD");
    return reply.dump();
  };
  round_trip(config, party, transport);
  require(calls == 2, "restart did not unwrap original key again");
  reply["plaintextCrc32c"] = "0";
  rejects([&] { unwrap_storage_key(config, party, transport); });
  reply.erase("plaintextCrc32c");
  rejects([&] { unwrap_storage_key(config, party, transport); });
  reply["plaintextCrc32c"] = "0";
  for (const auto& malformed : {"%%%", "AQID", "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=\n"}) {
    reply["plaintext"] = malformed;
    rejects([&] { unwrap_storage_key(config, party, transport); });
  }
  auto invalid = config;
  invalid["encryptionKeyFile"] = "/must-not-be-read";
  const auto before = calls;
  rejects([&] { unwrap_storage_key(invalid, party, transport); });
  invalid = config; invalid["testStorageKeyHex"] = std::string(64, '0');
  rejects([&] { unwrap_storage_key(invalid, party, transport); });
  invalid = config; invalid["storageKey"]["kmsKeyName"] = "https://attacker.invalid/key";
  rejects([&] { unwrap_storage_key(invalid, party, transport); });
  invalid = config; invalid["storageKey"]["wrappedKey"] = "not-base64";
  rejects([&] { unwrap_storage_key(invalid, party, transport); });
  rejects([&] { unwrap_storage_key(config, "different-holder", transport); });
  require(calls == before, "invalid config reached cloud transport");
  rejects([&] { unwrap_storage_key(config, party, [](const auto&, const auto&) -> std::string {
    throw std::runtime_error("permission denied");
  }); });
  rejects([&] { unwrap_storage_key(config, party, [](const auto&, const auto&) { return "not-json"; }); });
  std::cout << "PASS KMS checksums, malformed/denied responses, no file fallback, memory key and cold store reopen\n";
}
void live() {
  // Test-only stdin credentials: never installed or accepted by wallet-mpc-signer.
  std::string input, line;
  while (std::getline(std::cin, line)) { input += line; require(input.size() < 32768, "live input exceeds limit"); }
  auto test = Json::parse(input);
  OPENSSL_cleanse(input.data(), input.size());
  auto token = test.at("accessToken").get<std::string>();
  auto decrypt = [&](const std::string& name, const std::string& request) {
    return google_kms_decrypt(test.at("config"), name, request, token);
  };
  try {
    round_trip(test.at("config"), test.at("party").get<std::string>(), decrypt);
  } catch (...) { OPENSSL_cleanse(token.data(), token.size()); throw; }
  OPENSSL_cleanse(token.data(), token.size());
  auto& original = test.at("accessToken").get_ref<std::string&>();
  OPENSSL_cleanse(original.data(), original.size());
  std::cout << "PASS live GCP KMS decrypt, CRC32C and original encrypted store reopen; no plaintext key file\n";
}
}  // namespace
int main(int argc, char** argv) {
  try {
    if (argc == 2 && std::string(argv[1]) == "--live") live();
    else if (argc == 1) isolated();
    else throw std::runtime_error("invalid test arguments");
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "KMS acceptance failed: " << error.what() << "\n";
    return 1;
  }
}
