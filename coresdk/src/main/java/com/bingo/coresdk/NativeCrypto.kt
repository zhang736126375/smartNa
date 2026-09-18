package com.bingo.coresdk

internal object NativeCrypto {
    external fun nativeEncrypt(plainText: String): String
    external fun nativeDecrypt(cipherBase64: String): String
}
