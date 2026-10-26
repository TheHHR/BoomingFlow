package com.mardous.booming.data.remote.lyrics.api.unison

import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Generates and stores the per-device EC P-256 identity used to authenticate
 * requests to the Unison lyrics API. The keyId is the SHA-256 hex digest of
 * the canonical public-key JWK, matching the WebCrypto convention the API
 * expects. Only the keyId is needed for lyrics requests.
 */
class UnisonKeyProvider(private val preferences: SharedPreferences) {

    @Volatile
    private var cachedKeyId: String? = null

    @Synchronized
    fun getKeyId(): String {
        cachedKeyId?.let { return it }
        val stored = preferences.getString(KEY_IDENTITY, null)
        val keyId = stored?.let(::parseKeyId) ?: generateIdentity()
        cachedKeyId = keyId
        return keyId
    }

    private fun parseKeyId(stored: String): String? = runCatching {
        val identity = Json.parseToJsonElement(stored).jsonObject
        val x = identity["x"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val y = identity["y"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val keyId = identity["keyId"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        // The stored keyId must still match the public key it claims to hash.
        if (computeKeyId(x, y) == keyId) keyId else null
    }.getOrNull()

    private fun generateIdentity(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()

        val publicKey = keyPair.public as ECPublicKey
        val x = publicKey.w.affineX.toUnsigned32Bytes().toBase64Url()
        val y = publicKey.w.affineY.toUnsigned32Bytes().toBase64Url()
        val keyId = computeKeyId(x, y)

        val identity: JsonObject = buildJsonObject {
            put("keyId", keyId)
            put("crv", "P-256")
            put("kty", "EC")
            put("x", x)
            put("y", y)
            put("d", (keyPair.private as ECPrivateKey).s.toUnsigned32Bytes().toBase64Url())
        }
        preferences.edit { putString(KEY_IDENTITY, identity.toString()) }
        return keyId
    }

    private fun computeKeyId(x: String, y: String): String {
        val canonical = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun BigInteger.toUnsigned32Bytes(): ByteArray {
        val array = toByteArray()
        return when {
            array.size == 32 -> array
            array.size > 32 -> array.copyOfRange(array.size - 32, array.size)
            else -> ByteArray(32).also { padded -> array.copyInto(padded, 32 - array.size) }
        }
    }

    private fun ByteArray.toBase64Url(): String =
        Base64.encodeToString(this, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    companion object {
        private const val KEY_IDENTITY = "unison_key_identity_v1"
    }
}
