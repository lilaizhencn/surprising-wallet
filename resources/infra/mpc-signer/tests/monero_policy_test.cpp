#include <wallet_mpc/monero_policy.h>
#include <algorithm>
#include <iostream>
#include <stdexcept>

using nlohmann::json;
using wallet_mpc::canonical_monero_payment_description;

static void require(bool valid, const char* message) {
  if (!valid) throw std::runtime_error(message);
}

int main() {
  const json original{{"fee", 10}, {"amount_in", 100}, {"amount_out", 90}, {"change_amount", 20},
      {"change_address", "source"}, {"unlock_time", 0}, {"ring_size", 16}, {"payment_id", "00000000"},
      {"extra", "aabb"}, {"weight", 1000}, {"recipients", json::array({{{"address", "target"}, {"amount", 70}}})},
      {"sources", json::array({{{"pubkey", "bb"}, {"global_index", 2}, {"amount", 40}, {"rct", true}},
                              {{"pubkey", "aa"}, {"global_index", 1}, {"amount", 60}, {"rct", true}}})}};
  auto reordered = original;
  std::reverse(reordered["sources"].begin(), reordered["sources"].end());
  require(canonical_monero_payment_description(original) == canonical_monero_payment_description(reordered),
      "the original reviewed input set must survive native reconstruction ordering");
  for (const auto* field : {"fee", "amount_in", "amount_out", "change_amount", "unlock_time", "ring_size", "weight"}) {
    auto changed = reordered; changed[field] = original[field].get<int>() + 1;
    require(canonical_monero_payment_description(changed) != canonical_monero_payment_description(original),
        "input ordering normalization erased another payment field");
  }
  for (const auto* field : {"pubkey", "global_index", "amount", "rct"}) {
    auto changed = reordered; changed["sources"][0][field] = nullptr;
    require(canonical_monero_payment_description(changed) != canonical_monero_payment_description(original),
        "input ordering normalization accepted a changed original source");
  }
  auto changed = reordered; changed["sources"].erase(0);
  require(canonical_monero_payment_description(changed) != canonical_monero_payment_description(original), "original input deletion accepted");
  changed = reordered; changed["sources"].push_back(changed["sources"][0]);
  require(canonical_monero_payment_description(changed) != canonical_monero_payment_description(original), "duplicate original input accepted");
  changed = reordered; changed["recipients"][0]["address"] = "foreign";
  require(canonical_monero_payment_description(changed) != canonical_monero_payment_description(original), "recipient substitution accepted");
  changed = reordered; changed["change_address"] = "foreign";
  require(canonical_monero_payment_description(changed) != canonical_monero_payment_description(original), "change substitution accepted");
  changed = reordered; changed["extra"] = "bbcc";
  require(canonical_monero_payment_description(changed) != canonical_monero_payment_description(original), "extra substitution accepted");
  std::cout << "Native Monero exact reviewed input set and payment field preservation passed.\n";
}
