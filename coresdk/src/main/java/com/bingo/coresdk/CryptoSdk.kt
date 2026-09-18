package com.bingo.coresdk

object CryptoSdk {
    /**
     * AES-256-GCM 加密。返回 Base64(nonce||ciphertext||tag)。
     * Demo 密钥在 native 内置，生产环境不要使用。
     */
    fun encrypt(plainText: String): String = NativeCrypto.nativeEncrypt(plainText)

    fun decrypt(cipherBase64: String): String = NativeCrypto.nativeDecrypt(cipherBase64)
}
