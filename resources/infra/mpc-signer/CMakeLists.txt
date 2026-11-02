cmake_minimum_required(VERSION 3.24)
project(wallet_mpc_signer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "Build type" FORCE)
endif()

# The native dependency and its patched OpenSSL must be built together. Never
# silently link the host's unpatched libcrypto instead.
set(CBMPC_REVISION "0b716706f998633912ee2be9b9bf8909aed66088")
set(CBMPC_SOURCE_DIR "" CACHE PATH "Existing checkout of the pinned cb-mpc revision")
set(CBMPC_OPENSSL_ROOT "" CACHE PATH "Patched OpenSSL built for this cb-mpc revision")
if(NOT EXISTS "${CBMPC_OPENSSL_ROOT}/include/openssl/opensslv.h")
  message(FATAL_ERROR "Set CBMPC_OPENSSL_ROOT to the patched OpenSSL installation")
endif()

if(CBMPC_SOURCE_DIR)
  execute_process(COMMAND git -C "${CBMPC_SOURCE_DIR}" rev-parse HEAD
    OUTPUT_VARIABLE actual_revision OUTPUT_STRIP_TRAILING_WHITESPACE
    RESULT_VARIABLE revision_status)
  if(NOT revision_status EQUAL 0 OR NOT actual_revision STREQUAL CBMPC_REVISION)
    message(FATAL_ERROR "CBMPC_SOURCE_DIR must point at ${CBMPC_REVISION}")
  endif()
else()
  include(FetchContent)
  FetchContent_Declare(cbmpc_source
    GIT_REPOSITORY https://github.com/coinbase/cb-mpc.git
    GIT_TAG ${CBMPC_REVISION}
    GIT_SUBMODULES vendors/secp256k1)
  FetchContent_GetProperties(cbmpc_source)
  if(NOT cbmpc_source_POPULATED)
    FetchContent_Populate(cbmpc_source)
  endif()
  set(CBMPC_SOURCE_DIR "${cbmpc_source_SOURCE_DIR}")
endif()

set(BUILD_TESTS OFF CACHE BOOL "Do not build upstream's internal test suite" FORCE)
set(BUILD_DUDECT OFF CACHE BOOL "Do not build upstream's timing harness" FORCE)
set(ENABLE_VALIDATION ON CACHE BOOL "Keep cb-mpc input validation enabled" FORCE)
add_subdirectory("${CBMPC_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/cbmpc" EXCLUDE_FROM_ALL)
find_package(Threads REQUIRED)

add_library(wallet_mpc_engine src/engine.cpp src/store.cpp src/storage_key.cpp)
target_include_directories(wallet_mpc_engine PUBLIC include)
target_include_directories(wallet_mpc_engine SYSTEM PUBLIC "${CBMPC_SOURCE_DIR}/include")
target_link_libraries(wallet_mpc_engine PUBLIC cbmpc Threads::Threads)
target_compile_options(wallet_mpc_engine PRIVATE -Wall -Wextra -Werror)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
  target_compile_options(wallet_mpc_engine PUBLIC -mpclmul -maes -msse4.1)
endif()

enable_testing()
add_executable(wallet_mpc_engine_test tests/engine_test.cpp)
target_link_libraries(wallet_mpc_engine_test PRIVATE wallet_mpc_engine)
target_compile_options(wallet_mpc_engine_test PRIVATE -Wall -Wextra -Werror -Wno-deprecated-declarations)
add_test(NAME mpc_engine_quorums COMMAND wallet_mpc_engine_test)
set_tests_properties(mpc_engine_quorums PROPERTIES TIMEOUT 300)
add_executable(wallet_mpc_store_test tests/store_test.cpp)
target_link_libraries(wallet_mpc_store_test PRIVATE wallet_mpc_engine)
target_compile_options(wallet_mpc_store_test PRIVATE -Wall -Wextra -Werror)
add_test(NAME mpc_encrypted_store COMMAND wallet_mpc_store_test)

option(WALLET_MPC_BUILD_SERVER "Build the independent authenticated signing node" ON)
if(WALLET_MPC_BUILD_SERVER)
  if(NOT EXISTS "${CBMPC_OPENSSL_ROOT}/lib/libssl.a")
    message(FATAL_ERROR "Node server requires patched OpenSSL with TLS enabled; use build-and-test.sh")
  endif()
  file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/headers")
  file(DOWNLOAD
    "https://raw.githubusercontent.com/yhirose/cpp-httplib/v0.64.0/httplib.h"
    "${CMAKE_BINARY_DIR}/headers/httplib.h"
    EXPECTED_HASH SHA256=805169049e6a6e9ba7729d7e01d14ced825029c88a277586602a6b38df4d5357
    TLS_VERIFY ON TIMEOUT 90)
  file(DOWNLOAD
    "https://raw.githubusercontent.com/nlohmann/json/v3.12.0/single_include/nlohmann/json.hpp"
    "${CMAKE_BINARY_DIR}/headers/json.hpp"
    EXPECTED_HASH SHA256=aaf127c04cb31c406e5b04a63f1ae89369fccde6d8fa7cdda1ed4f32dfc5de63
    TLS_VERIFY ON TIMEOUT 90)
  add_executable(wallet-mpc-signer src/server.cpp src/monero.cpp src/authorization.cpp src/kms.cpp)
  target_include_directories(wallet-mpc-signer SYSTEM PRIVATE "${CMAKE_BINARY_DIR}/headers")
  target_compile_definitions(wallet-mpc-signer PRIVATE CPPHTTPLIB_OPENSSL_SUPPORT
    CPPHTTPLIB_DISABLE_MACOSX_AUTOMATIC_ROOT_CERTIFICATES)
  target_compile_options(wallet-mpc-signer PRIVATE -Wall -Wextra -Werror)
  target_link_libraries(wallet-mpc-signer PRIVATE wallet_mpc_engine
    "${CBMPC_OPENSSL_ROOT}/lib/libssl.a" "${CBMPC_OPENSSL_ROOT}/lib/libcrypto.a")
  install(TARGETS wallet-mpc-signer RUNTIME DESTINATION bin COMPONENT Runtime)
  install(FILES config/node.example.json DESTINATION share/wallet-mpc-signer COMPONENT Runtime)
  install(FILES README.md OPERATIONS.md DESTINATION share/wallet-mpc-signer COMPONENT Runtime)
  install(FILES systemd/surprising-wallet-mpc.service DESTINATION share/wallet-mpc-signer COMPONENT Runtime)
  add_executable(wallet_mpc_monero_policy_test tests/monero_policy_test.cpp)
  target_include_directories(wallet_mpc_monero_policy_test PRIVATE include)
  target_include_directories(wallet_mpc_monero_policy_test SYSTEM PRIVATE "${CMAKE_BINARY_DIR}/headers")
  target_compile_options(wallet_mpc_monero_policy_test PRIVATE -Wall -Wextra -Werror)
  add_test(NAME mpc_monero_reviewed_policy COMMAND wallet_mpc_monero_policy_test)
  # Isolated crash injection binary is test-only and never installed in a Runtime package.
  add_executable(wallet_mpc_admission_crash_test_node src/server.cpp src/monero.cpp src/authorization.cpp src/kms.cpp)
  target_include_directories(wallet_mpc_admission_crash_test_node SYSTEM PRIVATE "${CMAKE_BINARY_DIR}/headers")
  target_compile_definitions(wallet_mpc_admission_crash_test_node PRIVATE CPPHTTPLIB_OPENSSL_SUPPORT
    CPPHTTPLIB_DISABLE_MACOSX_AUTOMATIC_ROOT_CERTIFICATES WALLET_MPC_TEST_FAILPOINTS WALLET_MPC_TEST_STORAGE_KEY)
  target_compile_options(wallet_mpc_admission_crash_test_node PRIVATE -Wall -Wextra -Werror)
  target_link_libraries(wallet_mpc_admission_crash_test_node PRIVATE wallet_mpc_engine
    "${CBMPC_OPENSSL_ROOT}/lib/libssl.a" "${CBMPC_OPENSSL_ROOT}/lib/libcrypto.a")
  # No test key loader or cloud credential input is included in Runtime installs.
  add_executable(wallet_mpc_fixture_node src/server.cpp src/monero.cpp src/authorization.cpp src/kms.cpp)
  target_include_directories(wallet_mpc_fixture_node SYSTEM PRIVATE "${CMAKE_BINARY_DIR}/headers")
  target_compile_definitions(wallet_mpc_fixture_node PRIVATE CPPHTTPLIB_OPENSSL_SUPPORT
    CPPHTTPLIB_DISABLE_MACOSX_AUTOMATIC_ROOT_CERTIFICATES WALLET_MPC_TEST_STORAGE_KEY)
  target_compile_options(wallet_mpc_fixture_node PRIVATE -Wall -Wextra -Werror)
  target_link_libraries(wallet_mpc_fixture_node PRIVATE wallet_mpc_engine
    "${CBMPC_OPENSSL_ROOT}/lib/libssl.a" "${CBMPC_OPENSSL_ROOT}/lib/libcrypto.a")
  add_executable(wallet_mpc_kms_test tests/kms_test.cpp src/kms.cpp)
  target_include_directories(wallet_mpc_kms_test SYSTEM PRIVATE "${CMAKE_BINARY_DIR}/headers")
  target_compile_definitions(wallet_mpc_kms_test PRIVATE CPPHTTPLIB_OPENSSL_SUPPORT
    CPPHTTPLIB_DISABLE_MACOSX_AUTOMATIC_ROOT_CERTIFICATES WALLET_MPC_TEST_HTTPS_PROXY)
  target_compile_options(wallet_mpc_kms_test PRIVATE -Wall -Wextra -Werror)
  target_link_libraries(wallet_mpc_kms_test PRIVATE wallet_mpc_engine
    "${CBMPC_OPENSSL_ROOT}/lib/libssl.a" "${CBMPC_OPENSSL_ROOT}/lib/libcrypto.a")
  add_test(NAME mpc_kms_storage_key COMMAND wallet_mpc_kms_test)
  find_package(Python3 3.9 REQUIRED COMPONENTS Interpreter)
  add_test(NAME mpc_kms_production_startup
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/kms_startup_test.py" $<TARGET_FILE:wallet-mpc-signer>)
  set_tests_properties(mpc_kms_production_startup PROPERTIES TIMEOUT 45)
  add_test(NAME mpc_authorization_admission_crash
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/authorization_admission_crash_test.py"
      $<TARGET_FILE:wallet-mpc-signer> $<TARGET_FILE:wallet_mpc_admission_crash_test_node>)
  set_tests_properties(mpc_authorization_admission_crash PROPERTIES TIMEOUT 90)
  add_test(NAME mpc_independent_authorization
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/authorization_test.py" $<TARGET_FILE:wallet-mpc-signer>)
  set_tests_properties(mpc_independent_authorization PROPERTIES TIMEOUT 180)
  add_test(NAME mpc_independent_nodes
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/node_test.py" $<TARGET_FILE:wallet-mpc-signer>)
  set_tests_properties(mpc_independent_nodes PROPERTIES TIMEOUT 660)
  add_test(NAME mpc_network_refresh
    COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/epoch_refresh_test.py" $<TARGET_FILE:wallet-mpc-signer>)
  set_tests_properties(mpc_network_refresh PROPERTIES TIMEOUT 360)
  set(WALLET_MPC_MONERO_BIN_DIR "" CACHE PATH "Official Monero 0.18.5.4 binaries for isolated native component acceptance")
  if(WALLET_MPC_MONERO_BIN_DIR)
    add_test(NAME mpc_monero_native
      COMMAND "${Python3_EXECUTABLE}" "${CMAKE_CURRENT_SOURCE_DIR}/tests/monero_native_test.py"
        $<TARGET_FILE:wallet-mpc-signer> "${WALLET_MPC_MONERO_BIN_DIR}")
    set_tests_properties(mpc_monero_native PROPERTIES TIMEOUT 300)
  endif()
endif()
