#include <wallet_mpc/storage_key.h>

#include <cstring>
#include <stdexcept>
#include <sys/mman.h>
#include <unistd.h>
#include <openssl/crypto.h>

namespace wallet_mpc {
StorageKey::StorageKey(coinbase::mem_t bytes) {
  if (bytes.size != 32 || !bytes.data) throw std::invalid_argument("store requires a 32-byte key");
  const long size = sysconf(_SC_PAGESIZE);
  if (size <= 0) throw std::runtime_error("cannot determine secure key page size");
  page_size_ = static_cast<size_t>(size);
  void* allocation = mmap(nullptr, page_size_, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (allocation == MAP_FAILED) throw std::runtime_error("cannot allocate secure storage key");
  page_ = static_cast<unsigned char*>(allocation);
  if (mlock(page_, page_size_) != 0) {
    munmap(page_, page_size_); page_ = nullptr;
    throw std::runtime_error("cannot lock storage key memory");
  }
#ifdef MADV_DONTDUMP
  if (madvise(page_, page_size_, MADV_DONTDUMP) != 0) {
    munlock(page_, page_size_); munmap(page_, page_size_); page_ = nullptr;
    throw std::runtime_error("cannot exclude storage key from memory dumps");
  }
#endif
  std::memcpy(page_, bytes.data, 32);
}
StorageKey::~StorageKey() {
  if (page_) {
    OPENSSL_cleanse(page_, page_size_);
    munlock(page_, page_size_);
    munmap(page_, page_size_);
  }
}
StorageKey::StorageKey(StorageKey&& other) noexcept : page_(other.page_), page_size_(other.page_size_) {
  other.page_ = nullptr;
}
StorageKey::operator coinbase::mem_t() const { return coinbase::mem_t(page_, page_ ? 32 : 0); }
}  // namespace wallet_mpc
