#pragma once

#include <functional>
#include <string>
#include <json.hpp>
#include <wallet_mpc/storage_key.h>

namespace wallet_mpc {
using KmsDecrypt = std::function<std::string(const std::string&, const std::string&)>;
uint32_t crc32c(coinbase::mem_t bytes);
// The callback is a transport seam for isolated tests. The installed node uses
// only the fixed Google endpoint and its attached VM identity.
StorageKey unwrap_storage_key(const nlohmann::json& config, const std::string& party, const KmsDecrypt& decrypt);
StorageKey load_storage_key(const nlohmann::json& config, const std::string& party);
std::string google_kms_decrypt(const nlohmann::json& config, const std::string& key_name,
                              const std::string& request, const std::string& access_token);
}  // namespace wallet_mpc
