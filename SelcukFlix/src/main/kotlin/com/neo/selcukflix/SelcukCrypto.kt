// ! Bu araç NeO tarafından yazılmıştır.
// !
// ! SelcukFlix, sayfa içine gömdüğü `secureData` alanını (dizi/film detayları, oynatıcı
// ! kaynakları, arama sonuçları vb.) tarayıcıda çözülen AES-256-CBC ile şifreliyor.
// ! Anahtar/algoritma, sitenin kendi JS paketinden (webpack modülü 379) doğrulanarak
// ! çıkarılmıştır:
// !   key = base64(sha256("!!22xx!!90!!")).substring(0, 32)   (UTF-8 bayt olarak kullanılıyor)
// !   iv  = 16 sıfır bayt
// !   AES/CBC/PKCS7 (Java tarafında PKCS5Padding ile birebir aynı)

package com.neo.selcukflix

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SelcukCrypto {
    private const val PASSPHRASE = "!!22xx!!90!!"

    private val KEY: ByteArray by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(PASSPHRASE.toByteArray(Charsets.UTF_8))
        val b64    = Base64.encodeToString(digest, Base64.NO_WRAP)
        b64.substring(0, 32).toByteArray(Charsets.UTF_8)
    }

    private val IV = ByteArray(16) // 16 sıfır bayt

    /**
     * secureData (base64) -> çözülmüş JSON metni
     */
    fun decrypt(base64Cipher: String): String? {
        return try {
            val cipherBytes = Base64.decode(base64Cipher, Base64.DEFAULT)
            val cipher      = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))

            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
