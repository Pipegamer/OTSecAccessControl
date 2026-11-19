package cl.inacap.otsecaccesscontrol

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {

    // 1. DATOS EN REPOSO: Genera un Salt criptográfico aleatorio único de 16 bytes
    fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    // 2. DATOS EN REPOSO: Calcula el Hash SHA-256(salt + password) para guardar en SQLite
    fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = (salt + password).toByteArray(Charsets.UTF_8)
        val hashBytes = digest.digest(input)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // 3. DATOS EN TRÁNSITO: Cifrado simétrico AES-256-CBC para proteger credenciales y tramas IoT en red
    private val TRANSIT_KEY = "OTSecIndustrialKey2026_ISO27400!".toByteArray(Charsets.UTF_8) // 32 bytes (256 bits)
    private val TRANSIT_IV = "OTSecInitVector!".toByteArray(Charsets.UTF_8) // 16 bytes

    fun encryptInTransit(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(TRANSIT_KEY, "AES")
            val ivSpec = IvParameterSpec(TRANSIT_IV)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            plainText
        }
    }

    fun decryptInTransit(cipherText: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(TRANSIT_KEY, "AES")
            val ivSpec = IvParameterSpec(TRANSIT_IV)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decoded = Base64.decode(cipherText, Base64.NO_WRAP)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (e: Exception) {
            cipherText
        }
    }

    // 4. VALIDACIÓN DE CONTRASEÑA SEGURA: Rechaza PINs débiles (1234, 5678) y exige complejidad
    fun isStrongPassword(password: String): Boolean {
        val weakPins = listOf("1234", "5678", "0000", "1111", "123456", "12345678", "admin", "password")
        if (password.lowercase() in weakPins) return false
        if (password.length < 8) return false
        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        return hasUpper && hasLower && hasDigit
    }
}