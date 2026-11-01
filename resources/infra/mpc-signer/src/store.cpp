#include <wallet_mpc/store.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <stdexcept>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/rand.h>

namespace wallet_mpc {
namespace {
using namespace coinbase;
constexpr std::array<uint8_t, 8> magic{'S', 'W', 'M', 'P', 'C', '0', '1', '\n'};
constexpr int nonce_size = 12;
constexpr int tag_size = 16;
constexpr int max_record_size = 1024 * 1024;
constexpr int overhead = magic.size() + nonce_size + tag_size;

class File {
 public:
  explicit File(int descriptor) : descriptor_(descriptor) {}
  ~File() { if (descriptor_ >= 0) close(descriptor_); }
  int get() const { return descriptor_; }
  File(const File&) = delete;
  File& operator=(const File&) = delete;
 private:
  int descriptor_;
};

void require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

std::string context(const std::string& id, const std::string& metadata) {
  if (id.empty() || id.size() > 128 || metadata.empty() || metadata.size() > 65536 ||
      !std::all_of(id.begin(), id.end(), [](unsigned char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
               (c >= '0' && c <= '9') || c == '-' || c == '_';
      })) {
    throw std::invalid_argument("invalid encrypted record identity or metadata");
  }
  return "surprising-wallet-mpc-store-v1\n" + id + "\n" + metadata;
}

buf_t seal(mem_t key, const std::string& aad, mem_t plaintext) {
  require(plaintext.size >= 0 && plaintext.size <= max_record_size &&
              (plaintext.size == 0 || plaintext.data), "invalid encrypted record size");
  buf_t result(plaintext.size + overhead);
  std::copy(magic.begin(), magic.end(), result.data());
  auto nonce = result.data() + magic.size();
  require(RAND_bytes(nonce, nonce_size) == 1, "record nonce generation failed");
  std::unique_ptr<EVP_CIPHER_CTX, decltype(&EVP_CIPHER_CTX_free)> cipher(EVP_CIPHER_CTX_new(), EVP_CIPHER_CTX_free);
  int written = 0, final = 0;
  require(cipher && EVP_EncryptInit_ex(cipher.get(), EVP_aes_256_gcm(), nullptr, key.data, nonce) == 1 &&
              EVP_EncryptUpdate(cipher.get(), nullptr, &written,
                                reinterpret_cast<const uint8_t*>(aad.data()), static_cast<int>(aad.size())) == 1 &&
              EVP_EncryptUpdate(cipher.get(), result.data() + magic.size() + nonce_size, &written,
                                plaintext.data, plaintext.size) == 1,
          "record encryption failed");
  require(EVP_EncryptFinal_ex(cipher.get(), result.data() + magic.size() + nonce_size + written, &final) == 1 &&
              written + final == plaintext.size &&
              EVP_CIPHER_CTX_ctrl(cipher.get(), EVP_CTRL_GCM_GET_TAG, tag_size,
                                  result.data() + result.size() - tag_size) == 1,
          "record encryption failed");
  return result;
}

buf_t unseal(mem_t key, const std::string& aad, mem_t sealed) {
  require(sealed.size >= overhead && sealed.size <= max_record_size + overhead &&
              std::equal(magic.begin(), magic.end(), sealed.data), "invalid encrypted record format");
  const int size = sealed.size - overhead;
  buf_t result(size + EVP_MAX_BLOCK_LENGTH);
  std::unique_ptr<EVP_CIPHER_CTX, decltype(&EVP_CIPHER_CTX_free)> cipher(EVP_CIPHER_CTX_new(), EVP_CIPHER_CTX_free);
  int written = 0, final = 0;
  require(cipher && EVP_DecryptInit_ex(cipher.get(), EVP_aes_256_gcm(), nullptr,
                                      key.data, sealed.data + magic.size()) == 1 &&
              EVP_DecryptUpdate(cipher.get(), nullptr, &written,
                                reinterpret_cast<const uint8_t*>(aad.data()), static_cast<int>(aad.size())) == 1 &&
              EVP_DecryptUpdate(cipher.get(), result.data(), &written,
                                sealed.data + magic.size() + nonce_size, size) == 1 &&
              EVP_CIPHER_CTX_ctrl(cipher.get(), EVP_CTRL_GCM_SET_TAG, tag_size,
                                  const_cast<uint8_t*>(sealed.data + sealed.size - tag_size)) == 1 &&
              EVP_DecryptFinal_ex(cipher.get(), result.data() + written, &final) == 1 &&
              written + final == size,
          "encrypted record authentication failed");
  result.resize(size);
  return result;
}
}  // namespace

EncryptedStore::EncryptedStore(const std::string& directory, mem_t encryption_key) : directory_fd_(-1) {
  if (encryption_key.size != 32 || !encryption_key.data) throw std::invalid_argument("store requires a 32-byte key");
  if (mkdir(directory.c_str(), 0700) != 0 && errno != EEXIST) throw std::runtime_error("cannot create store directory");
  const int fd = open(directory.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
  require(fd >= 0, "cannot open store directory");
  File guard(fd);
  struct stat status{};
  require(fstat(fd, &status) == 0 && S_ISDIR(status.st_mode) && status.st_uid == geteuid() &&
              (status.st_mode & 0777) == 0700, "store directory must belong to this user and have mode 0700");
  require(flock(fd, LOCK_EX | LOCK_NB) == 0, "encrypted store is already in use by another node");
  encryption_key_ = encryption_key;
  directory_fd_ = fcntl(fd, F_DUPFD_CLOEXEC, 0);
  require(directory_fd_ >= 0, "cannot retain store directory");
}

EncryptedStore::~EncryptedStore() {
  if (directory_fd_ >= 0) close(directory_fd_);
  encryption_key_.secure_bzero();
}

std::optional<coinbase::buf_t> EncryptedStore::get(const std::string& id, const std::string& metadata) const {
  std::lock_guard<std::mutex> lock(mutex_);
  return read_record(id, metadata);
}

std::optional<coinbase::buf_t> EncryptedStore::read_record(const std::string& id, const std::string& metadata) const {
  const auto aad = context(id, metadata);
  const int fd = openat(directory_fd_, id.c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
  if (fd < 0 && errno == ENOENT) return std::nullopt;
  require(fd >= 0, "cannot read encrypted record");
  File file(fd);
  struct stat status{};
  require(fstat(fd, &status) == 0 && S_ISREG(status.st_mode) && status.st_uid == geteuid() &&
              (status.st_mode & 0777) == 0600 && status.st_size >= overhead &&
              status.st_size <= max_record_size + overhead, "unsafe encrypted record file");
  buf_t sealed(static_cast<int>(status.st_size));
  int offset = 0;
  while (offset < sealed.size()) {
    const auto received = read(fd, sealed.data() + offset, sealed.size() - offset);
    if (received < 0 && errno == EINTR) continue;
    require(received > 0, "cannot read complete encrypted record");
    offset += static_cast<int>(received);
  }
  return unseal(encryption_key_, aad, sealed);
}

void EncryptedStore::put(const std::string& id, const std::string& metadata, mem_t plaintext) const {
  // Readers cannot expose a newly linked result before its directory fsync.
  std::lock_guard<std::mutex> lock(mutex_);
  const auto aad = context(id, metadata);
  auto sealed = seal(encryption_key_, aad, plaintext);
  uint8_t random[16];
  require(RAND_bytes(random, sizeof(random)) == 1, "temporary record name generation failed");
  const char* hex = "0123456789abcdef";
  std::string temporary = ".pending-";
  for (uint8_t byte : random) { temporary += hex[byte >> 4]; temporary += hex[byte & 15]; }
  const int fd = openat(directory_fd_, temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
  require(fd >= 0, "cannot create encrypted record");
  File file(fd);
  try {
    int offset = 0;
    while (offset < sealed.size()) {
      const auto written = write(fd, sealed.data() + offset, sealed.size() - offset);
      if (written < 0 && errno == EINTR) continue;
      require(written > 0, "cannot write encrypted record");
      offset += static_cast<int>(written);
    }
    require(fsync(fd) == 0, "cannot sync encrypted record");
    if (linkat(directory_fd_, temporary.c_str(), directory_fd_, id.c_str(), 0) != 0) {
      require(errno == EEXIST, "cannot publish encrypted record");
      auto existing = read_record(id, metadata);
      require(existing && existing->size() == plaintext.size &&
                  CRYPTO_memcmp(existing->data(), plaintext.data, plaintext.size) == 0,
              "encrypted record identity already has different content");
    }
    require(unlinkat(directory_fd_, temporary.c_str(), 0) == 0 && fsync(directory_fd_) == 0,
            "cannot sync encrypted record directory");
  } catch (...) {
    unlinkat(directory_fd_, temporary.c_str(), 0);
    throw;
  }
}
}  // namespace wallet_mpc
