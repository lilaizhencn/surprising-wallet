#pragma once

#include <json.hpp>
#include <cstdint>
#include <string>
#include <vector>

namespace wallet_mpc {
// Independently verifies the authority signature and every native execution field.
// Freshness is required only on first admission; callers persist the original proof before crypto.
nlohmann::json verify_authorization(const nlohmann::json& proof, const nlohmann::json& descriptor,
    const std::vector<std::string>& trusted_public_keys, int64_t now, bool require_fresh);
}
