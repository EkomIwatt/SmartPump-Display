// PBKDF2-HMAC-SHA256 PIN hashing. Resistant to offline brute-force of a 4-digit PIN —
// 10 000 possible PINs × 100 000 iterations means a stolen DB can still cost the attacker
// real CPU per device. A fresh 16-byte salt per device defeats rainbow tables across the
// fleet. Hash and salt are stored Base64-encoded alongside the identity row.
package app.balancee.smartpump.display.domain.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinHasher {

    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /** Generates a fresh 16-byte salt as Base64-NO_WRAP. */
    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Hashes [rawPin] with the given [saltBase64]; returns Base64-NO_WRAP. */
    fun hash(rawPin: String, saltBase64: String): String {
        val saltBytes = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(rawPin.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH_BITS)
        val key = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    /** Constant-time comparison so a timing side-channel can't sniff which digit is wrong. */
    fun verify(rawPin: String, saltBase64: String, expectedHashBase64: String): Boolean {
        val computed = hash(rawPin, saltBase64)
        val a = computed.toByteArray()
        val b = expectedHashBase64.toByteArray()
        // MessageDigest.isEqual is the standard constant-time comparator on Android.
        return MessageDigest.isEqual(a, b)
    }
}
