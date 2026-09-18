#ifndef CORE_CRYPTO_H
#define CORE_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CORE_OK 0
#define CORE_ERR_PARAM 1
#define CORE_ERR_FORMAT 2
#define CORE_ERR_AUTH 3
#define CORE_ERR_NOMEM 4
#define CORE_ERR_CRYPTO 5

int core_encrypt(const uint8_t* plain, size_t plain_len,
                 uint8_t** out, size_t* out_len);

int core_decrypt(const uint8_t* packed, size_t packed_len,
                 uint8_t** out, size_t* out_len);

void core_free(uint8_t* p);

#ifdef __cplusplus
}
#endif

#endif
