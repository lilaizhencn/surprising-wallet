#include <wallet_mpc/store.h>

#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

#include <openssl/rand.h>

namespace {
using namespace coinbase;
using wallet_mpc::EncryptedStore;

void require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

template <typename F>
void rejects(F operation) {
  try { operation(); } catch (const std::exception&) { return; }
  throw std::runtime_error("unsafe storage operation was accepted");
}

struct TemporaryDirectory {
  std::string path;
  TemporaryDirectory() {
    char pattern[] = "/tmp/surprising-wallet-mpc-store-test-XXXXXX";
    char* result = mkdtemp(pattern);
    require(result != nullptr, "cannot create isolated test directory");
    path = result;
  }
  ~TemporaryDirectory() { std::filesystem::remove_all(path); }
};

void exercise() {
  TemporaryDirectory temporary;
  buf_t key(32);
  require(RAND_bytes(key.data(), key.size()) == 1, "cannot generate test encryption key");
  const std::string metadata = "tenant=a;key=k;epoch=1;algorithm=ed25519;party=local-identity";
  const std::string secret = "synthetic-share-material-that-must-not-appear-on-disk";
  {
    EncryptedStore store(temporary.path, key);
    rejects([&] { EncryptedStore duplicate(temporary.path, key); });
    require(!store.get("missing", metadata), "missing record should be absent");
    store.put("share-1", metadata, mem_t(secret));
    store.put("share-1", metadata, mem_t(secret));
    const auto loaded = store.get("share-1", metadata);
    require(loaded && *loaded == mem_t(secret), "stored record did not round-trip");
    rejects([&] { store.put("share-1", metadata, mem_t("different material")); });
    rejects([&] { store.get("share-1", "tenant=another-tenant"); });
    rejects([&] { store.get("../share-1", metadata); });
    rejects([&] { store.put("share-2", "", mem_t(secret)); });

    std::ifstream file(temporary.path + "/share-1", std::ios::binary);
    const std::string ciphertext((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    require(ciphertext.find(secret) == std::string::npos, "plaintext secret is present on disk");
    struct stat status{};
    require(stat((temporary.path + "/share-1").c_str(), &status) == 0 && (status.st_mode & 0777) == 0600,
            "encrypted share must have mode 0600");

    std::filesystem::copy_file(temporary.path + "/share-1", temporary.path + "/copied-share");
    rejects([&] { store.get("copied-share", metadata); });
    std::filesystem::create_symlink(temporary.path + "/share-1", temporary.path + "/linked-share");
    rejects([&] { store.get("linked-share", metadata); });

    std::vector<std::exception_ptr> failures(6);
    std::vector<std::thread> writers;
    for (int i = 0; i < 6; ++i) writers.emplace_back([&, i] {
      try { store.put("concurrent-share", metadata, mem_t(secret)); }
      catch (...) { failures[i] = std::current_exception(); }
    });
    for (auto& writer : writers) writer.join();
    for (const auto& failure : failures) if (failure) std::rethrow_exception(failure);
    require(*store.get("concurrent-share", metadata) == mem_t(secret), "concurrent writes changed the share");
  }
  {
    buf_t wrong_key(key);
    wrong_key[0] ^= 1;
    EncryptedStore wrong_store(temporary.path, wrong_key);
    rejects([&] { wrong_store.get("share-1", metadata); });
  }
  {
    EncryptedStore restarted(temporary.path, key);
    require(*restarted.get("share-1", metadata) == mem_t(secret), "share did not survive store restart");
    std::fstream change(temporary.path + "/share-1", std::ios::in | std::ios::out | std::ios::binary);
    change.seekg(22);
    char value = 0;
    change.read(&value, 1);
    value ^= 1;
    change.seekp(22);
    change.write(&value, 1);
    change.close();
    rejects([&] { restarted.get("share-1", metadata); });
  }
  TemporaryDirectory insecure;
  require(chmod(insecure.path.c_str(), 0755) == 0, "cannot prepare permission test");
  rejects([&] { EncryptedStore bad(insecure.path, key); });
  require(chmod(insecure.path.c_str(), 0700) == 0, "cannot restore test directory permissions");
  std::filesystem::create_directory_symlink(insecure.path, temporary.path + "/linked-directory");
  rejects([&] { EncryptedStore bad(temporary.path + "/linked-directory", key); });
}
}  // namespace

int main() {
  try {
    exercise();
    std::cout << "Encrypted store: restart, idempotency, concurrent publication, tenant/key binding, "
                 "tampering, wrong key, file permissions and symlink rejection passed\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "Encrypted store test failed: " << error.what() << '\n';
    return 1;
  }
}
