/**
 * core AES-256-GCM 实现（纯 C ABI，无 JNI）。
 *
 * 密文打包：nonce(12 字节) || ciphertext || tag(16 字节)。
 * 测试密钥写死在本文件，仅供 demo；生产环境必须外置密钥，不要把密钥放进 so 明文。
 */
#include "core/core_crypto.h"

#include "mbedtls/ctr_drbg.h"
#include "mbedtls/entropy.h"
#include "mbedtls/gcm.h"

#include <stdlib.h>
#include <string.h>

static const uint8_t kDemoAes256Key[32] = {
    'D','E','M','O','-','A','E','S','-','2','5','6','-','G','C','M',
    '-','T','E','S','T','-','K','E','Y','-','!','!','!','!','!','!'
};

static const size_t kNonceLen = 12;
static const size_t kTagLen = 16;

void core_free(uint8_t* p) {
    free(p);
}

static int random_nonce(uint8_t* nonce) {
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context drbg;
    mbedtls_entropy_init(&entropy);
    mbedtls_ctr_drbg_init(&drbg);
    const char pers[] = "core_gcm_demo";
    int rc = mbedtls_ctr_drbg_seed(
        &drbg, mbedtls_entropy_func, &entropy,
        reinterpret_cast<const unsigned char*>(pers),
        sizeof(pers) - 1);
    if (rc == 0) {
        rc = mbedtls_ctr_drbg_random(&drbg, nonce, kNonceLen);
    }
    mbedtls_ctr_drbg_free(&drbg);
    mbedtls_entropy_free(&entropy);
    return rc == 0 ? CORE_OK : CORE_ERR_CRYPTO;
}

int core_encrypt(const uint8_t* plain, size_t plain_len,
                 uint8_t** out, size_t* out_len) {
    if (out == nullptr || out_len == nullptr) {
        return CORE_ERR_PARAM;
    }
    if (plain == nullptr && plain_len != 0) {
        return CORE_ERR_PARAM;
    }
    *out = nullptr;
    *out_len = 0;

    const size_t packed_len = kNonceLen + plain_len + kTagLen;
    uint8_t* packed = static_cast<uint8_t*>(malloc(packed_len));
    if (packed == nullptr) {
        return CORE_ERR_NOMEM;
    }

    int rc = random_nonce(packed);
    if (rc != CORE_OK) {
        free(packed);
        return rc;
    }

    const uint8_t empty = 0;
    const uint8_t* input = (plain == nullptr) ? &empty : plain;

    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    rc = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, kDemoAes256Key, 256);
    if (rc == 0) {
        rc = mbedtls_gcm_crypt_and_tag(
            &gcm, MBEDTLS_GCM_ENCRYPT, plain_len,
            packed, kNonceLen,
            nullptr, 0,
            input,
            packed + kNonceLen,
            kTagLen, packed + kNonceLen + plain_len);
    }
    mbedtls_gcm_free(&gcm);
    if (rc != 0) {
        free(packed);
        return CORE_ERR_CRYPTO;
    }
    *out = packed;
    *out_len = packed_len;
    return CORE_OK;
}

int core_decrypt(const uint8_t* packed, size_t packed_len,
                 uint8_t** out, size_t* out_len) {
    if (out == nullptr || out_len == nullptr) {
        return CORE_ERR_PARAM;
    }
    if (packed == nullptr || packed_len < kNonceLen + kTagLen) {
        return CORE_ERR_FORMAT;
    }
    *out = nullptr;
    *out_len = 0;

    const size_t plain_len = packed_len - kNonceLen - kTagLen;
    uint8_t* plain = static_cast<uint8_t*>(malloc(plain_len == 0 ? 1 : plain_len));
    if (plain == nullptr) {
        return CORE_ERR_NOMEM;
    }

    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    int rc = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, kDemoAes256Key, 256);
    if (rc == 0) {
        rc = mbedtls_gcm_auth_decrypt(
            &gcm, plain_len,
            packed, kNonceLen,
            nullptr, 0,
            packed + kNonceLen + plain_len, kTagLen,
            packed + kNonceLen,
            plain);
    }
    mbedtls_gcm_free(&gcm);
    if (rc == MBEDTLS_ERR_GCM_AUTH_FAILED) {
        free(plain);
        return CORE_ERR_AUTH;
    }
    if (rc != 0) {
        free(plain);
        return CORE_ERR_CRYPTO;
    }
    *out = plain;
    *out_len = plain_len;
    return CORE_OK;
}
