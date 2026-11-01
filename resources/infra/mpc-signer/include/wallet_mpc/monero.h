#pragma once

#include <wallet_mpc/store.h>
#include <json.hpp>
#include <functional>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace wallet_mpc {
// Only controlled adapter diagnostics, never RPC bodies, passwords or ceremony material.
class MoneroError final : public std::invalid_argument {
 public:
  explicit MoneroError(const std::string& message) : std::invalid_argument(message) {}
};
// Native Monero wallet files and their encrypted passwords stay in this node's trust boundary.
// Exchange sends only native ceremony messages between the authenticated registered parties.
using MoneroExchange = std::function<std::vector<nlohmann::json>(const nlohmann::json&)>;
nlohmann::json create_monero_key(const nlohmann::json& descriptor, const nlohmann::json& configuration,
                                const std::string& local_party, EncryptedStore& store,
                                const MoneroExchange& exchange);
nlohmann::json sign_monero_payment(const nlohmann::json& descriptor, const nlohmann::json& configuration,
                                  const std::string& local_party, int self, EncryptedStore& store,
                                  const MoneroExchange& exchange);
// Only the original proposer may fetch its original co-signer's already persisted native material.
std::optional<nlohmann::json> monero_recovery_material(const nlohmann::json& descriptor,
                                                    const std::string& local_party, EncryptedStore& store);
nlohmann::json recover_monero_payment(const nlohmann::json& descriptor, const nlohmann::json& configuration,
    const std::string& local_party, int self, EncryptedStore& store,
    const std::function<std::optional<nlohmann::json>(const std::string&)>& fetch_cosigned);
nlohmann::json relay_monero_payment(const nlohmann::json& descriptor, const nlohmann::json& configuration,
                                   const std::string& local_party, EncryptedStore& store);
nlohmann::json observe_monero_account(const nlohmann::json& descriptor, const nlohmann::json& configuration,
                                     const std::string& local_party, EncryptedStore& store,
                                     const MoneroExchange& exchange);
}
