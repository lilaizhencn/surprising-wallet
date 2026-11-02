#pragma once

#include <cbmpc/core/buf.h>

namespace wallet_mpc {
// A single, non-copyable key allocation. Lock failure is fatal: never silently
// allow a storage key to be swapped to disk.
class StorageKey {
 public:
  explicit StorageKey(coinbase::mem_t bytes);
  ~StorageKey();
  StorageKey(StorageKey&& other) noexcept;
  StorageKey& operator=(StorageKey&&) = delete;
  StorageKey(const StorageKey&) = delete;
  StorageKey& operator=(const StorageKey&) = delete;
  operator coinbase::mem_t() const;
 private:
  unsigned char* page_ = nullptr;
  size_t page_size_ = 0;
};
}  // namespace wallet_mpc
