#pragma once

#include <optional>
#include <mutex>
#include <string>

#include <cbmpc/core/buf.h>

namespace wallet_mpc {

// Immutable encrypted records for one node. The caller supplies authenticated
// metadata including tenant, key id, epoch, algorithm and local party identity.
class EncryptedStore {
 public:
  EncryptedStore(const std::string& directory, coinbase::mem_t encryption_key);
  ~EncryptedStore();
  EncryptedStore(const EncryptedStore&) = delete;
  EncryptedStore& operator=(const EncryptedStore&) = delete;

  // Repeating the same write is idempotent; changing an existing record fails.
  void put(const std::string& id, const std::string& metadata, coinbase::mem_t plaintext) const;
  std::optional<coinbase::buf_t> get(const std::string& id, const std::string& metadata) const;

 private:
  int directory_fd_;
  coinbase::buf_t encryption_key_;
  mutable std::mutex mutex_;
  std::optional<coinbase::buf_t> read_record(const std::string& id, const std::string& metadata) const;
};
}  // namespace wallet_mpc
