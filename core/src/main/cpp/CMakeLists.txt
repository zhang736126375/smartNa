cmake_minimum_required(VERSION 3.22.1)
project(core)

set(MBEDTLS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/mbedtls)

add_library(mbedtls_crypto STATIC
    ${MBEDTLS_DIR}/library/aes.c
    ${MBEDTLS_DIR}/library/gcm.c
    ${MBEDTLS_DIR}/library/block_cipher.c
    ${MBEDTLS_DIR}/library/md.c
    ${MBEDTLS_DIR}/library/sha256.c
    ${MBEDTLS_DIR}/library/entropy.c
    ${MBEDTLS_DIR}/library/entropy_poll.c
    ${MBEDTLS_DIR}/library/ctr_drbg.c
    ${MBEDTLS_DIR}/library/platform.c
    ${MBEDTLS_DIR}/library/platform_util.c
    ${MBEDTLS_DIR}/library/constant_time.c
)
target_include_directories(mbedtls_crypto PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${MBEDTLS_DIR}/include
)
target_compile_definitions(mbedtls_crypto PUBLIC
    MBEDTLS_CONFIG_FILE="core_mbedtls_config.h"
)

add_library(core SHARED src/core_crypto.cpp)
target_include_directories(core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(core mbedtls_crypto log)
