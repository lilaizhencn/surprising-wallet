#pragma once

#include <json.hpp>
#include <algorithm>
#include <stdexcept>

namespace wallet_mpc {
// Review identity is the exact input set and all returned policy fields, independent of input order.
inline nlohmann::json canonical_monero_payment_description(nlohmann::json description) {
  auto& sources = description.at("sources");
  if (!sources.is_array()) throw std::invalid_argument("native Monero reviewed sources must be an array");
  std::sort(sources.begin(), sources.end(), [](const auto& left, const auto& right) { return left.dump() < right.dump(); });
  return description;
}
}
