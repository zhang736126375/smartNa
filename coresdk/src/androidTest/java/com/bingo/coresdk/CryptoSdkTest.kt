package com.bingo.coresdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CryptoSdkTest {

    @Before
    fun setUp() {
        CoreSdk.init()
    }

    @Test
    fun encryptDecrypt_restoresPlaintext() {
        val plain = "你好 SmartNa"
        val cipher = CryptoSdk.encrypt(plain)
        assertTrue(cipher.isNotEmpty())
        assertNotEquals(plain, cipher)
        assertEquals(plain, CryptoSdk.decrypt(cipher))
    }

    @Test
    fun encryptDecrypt_emptyString() {
        assertEquals("", CryptoSdk.decrypt(CryptoSdk.encrypt("")))
    }

    @Test
    fun decrypt_invalidBase64_throwsFormat() {
        try {
            CryptoSdk.decrypt("@@@not-base64@@@")
            throw AssertionError("expected CryptoException")
        } catch (e: CryptoException) {
            assertEquals("密文格式无效", e.message)
        }
    }

    @Test
    fun decrypt_tamperedCipher_throwsAuthOrFormat() {
        val cipher = CryptoSdk.encrypt("payload")
        val tampered = if (cipher.last() == 'A') cipher.dropLast(1) + "B" else cipher.dropLast(1) + "A"
        try {
            CryptoSdk.decrypt(tampered)
            throw AssertionError("expected CryptoException")
        } catch (e: CryptoException) {
            assertTrue(
                e.message == "解密失败，数据可能被篡改" || e.message == "密文格式无效"
            )
        }
    }
}
