package com.mardous.booming.data.remote.lyrics.spatial.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.UUID

/**
 * Per-device EC P-256 identity, ported from SpatialFlow.
 *
 * A fresh keypair is generated once per install and stored in
 * SharedPreferences. The SHA-256 hash of the canonical public JWK
 * becomes the 64-char hex `keyId` sent as `x-key-id` to Unison and
 * `X-Key-Id` on signed BetterLyrics requests.
 */
object DeviceKeyIdentityManager {
    private const val TAG = "DeviceKeyIdentity"
    private const val PREFS_NAME = "BoomingLyricsIdentity"
    private const val KEY_IDENTITY_JSON = "key_identity_v1"

    data class KeyIdentity(
        val keyId: String,
        val publicKeyJwk: Map<String, Any>,
        val privateKeyJwk: Map<String, Any>,
        val createdAt: Long,
        val displayName: String? = null
    )

    data class SignedPayloadResult(
        val payload: Map<String, Any>,
        val signature: String,
        val publicKey: Map<String, Any>
    )

    @Volatile
    private var cachedIdentity: KeyIdentity? = null

    @Volatile
    private var cachedPrivateKey: PrivateKey? = null

    private val gson = Gson()

    @Synchronized
    fun getOrCreateIdentity(context: Context): KeyIdentity {
        cachedIdentity?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedJson = prefs.getString(KEY_IDENTITY_JSON, null)
        if (!storedJson.isNullOrBlank()) {
            try {
                val identity = parseIdentityJson(storedJson)
                if (identity != null) {
                    cachedIdentity = identity
                    return identity
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse stored identity, generating new one", e)
            }
        }

        val newIdentity = generateNewIdentity()
        saveIdentity(context, newIdentity)
        cachedIdentity = newIdentity
        return newIdentity
    }

    fun getKeyId(context: Context): String = getOrCreateIdentity(context).keyId

    @Synchronized
    fun signPayload(
        context: Context,
        data: Map<String, Any?>,
        nonce: String = UUID.randomUUID().toString()
    ): SignedPayloadResult {
        val identity = getOrCreateIdentity(context)
        val timestamp = System.currentTimeMillis()

        val fullPayload = sortedMapOf<String, Any>()
        for ((k, v) in data) {
            if (v != null) fullPayload[k] = v
        }
        fullPayload["timestamp"] = timestamp
        fullPayload["nonce"] = nonce
        fullPayload["keyId"] = identity.keyId

        val canonicalPayloadJson = canonicalJson(fullPayload)
        val payloadBytes = canonicalPayloadJson.toByteArray(StandardCharsets.UTF_8)

        val privateKey = getOrLoadPrivateKey(identity)
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(payloadBytes)
        val derSig = signature.sign()

        val joseSig = derToJose(derSig, 64)
        val sigB64 = Base64.encodeToString(joseSig, Base64.NO_WRAP)

        return SignedPayloadResult(
            payload = fullPayload,
            signature = sigB64,
            publicKey = identity.publicKeyJwk
        )
    }

    private fun generateNewIdentity(): KeyIdentity {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        val pub = kp.public as ECPublicKey
        val ecPriv = kp.private as ECPrivateKey

        val xB64 = pub.w.affineX.toUnsigned32Bytes().toBase64Url()
        val yB64 = pub.w.affineY.toUnsigned32Bytes().toBase64Url()
        val dB64 = ecPriv.s.toUnsigned32Bytes().toBase64Url()

        val keyId = computeKeyId(xB64, yB64)

        val pubJwk = linkedMapOf<String, Any>(
            "alg" to "ES256",
            "crv" to "P-256",
            "ext" to true,
            "key_ops" to listOf("verify"),
            "kty" to "EC",
            "x" to xB64,
            "y" to yB64
        )

        val privJwk = linkedMapOf<String, Any>(
            "alg" to "ES256",
            "crv" to "P-256",
            "d" to dB64,
            "ext" to true,
            "key_ops" to listOf("sign"),
            "kty" to "EC",
            "x" to xB64,
            "y" to yB64
        )

        cachedPrivateKey = ecPriv

        return KeyIdentity(
            keyId = keyId,
            publicKeyJwk = pubJwk,
            privateKeyJwk = privJwk,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun computeKeyId(x: String, y: String): String {
        val canonical = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalJson(map: Map<String, Any>): String {
        val sortedKeys = map.keys.sorted()
        val elements = sortedKeys.map { k ->
            val v = map[k]
            "\"$k\":${gson.toJson(v)}"
        }
        return "{${elements.joinToString(",")}}"
    }

    private fun getOrLoadPrivateKey(identity: KeyIdentity): PrivateKey {
        cachedPrivateKey?.let { return it }

        val dB64 = identity.privateKeyJwk["d"] as? String
            ?: throw IllegalStateException("Missing d in privateKey")
        val dBytes = Base64.decode(dB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val dInt = BigInteger(1, dBytes)

        val kf = KeyFactory.getInstance("EC")
        val ecParamSpec = getSecp256r1Params()
        val privKeySpec = ECPrivateKeySpec(dInt, ecParamSpec)
        val priv = kf.generatePrivate(privKeySpec)
        cachedPrivateKey = priv
        return priv
    }

    private fun saveIdentity(context: Context, identity: KeyIdentity) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(identity)
        prefs.edit().putString(KEY_IDENTITY_JSON, json).apply()
    }

    private fun getSecp256r1Params(): ECParameterSpec {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return (kpg.generateKeyPair().public as ECPublicKey).params
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseIdentityJson(jsonString: String): KeyIdentity? {
        val root = JsonParser.parseString(jsonString).asJsonObject
        val keyId = root.get("keyId")?.asString ?: return null
        val pubElement = root.get("publicKey")?.asJsonObject ?: return null
        val privElement = root.get("privateKey")?.asJsonObject ?: return null
        val createdAt = root.get("createdAt")?.asLong ?: System.currentTimeMillis()
        val displayName = root.get("displayName")?.asString

        val pubMap = gson.fromJson(pubElement, Map::class.java) as Map<String, Any>
        val privMap = gson.fromJson(privElement, Map::class.java) as Map<String, Any>

        return KeyIdentity(
            keyId = keyId,
            publicKeyJwk = pubMap,
            privateKeyJwk = privMap,
            createdAt = createdAt,
            displayName = displayName
        )
    }

    private fun BigInteger.toUnsigned32Bytes(): ByteArray {
        val raw = this.toByteArray()
        if (raw.size == 32) return raw
        if (raw.size > 32) {
            var start = 0
            while (start < raw.size - 32 && raw[start] == 0.toByte()) {
                start++
            }
            return raw.copyOfRange(start, start + 32)
        }
        val padded = ByteArray(32)
        System.arraycopy(raw, 0, padded, 32 - raw.size, raw.size)
        return padded
    }

    private fun ByteArray.toBase64Url(): String {
        return Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun derToJose(derSig: ByteArray, joseLen: Int): ByteArray {
        var offset = 2
        val rLen = derSig[offset + 1].toInt()
        offset += 2
        val r = derSig.copyOfRange(offset, offset + rLen)
        offset += rLen
        val sLen = derSig[offset + 1].toInt()
        offset += 2
        val s = derSig.copyOfRange(offset, offset + sLen)

        val jose = ByteArray(joseLen)
        val halfLen = joseLen / 2

        val rTrimmed = if (r.size > halfLen && r[0] == 0.toByte()) r.copyOfRange(1, r.size) else r
        val sTrimmed = if (s.size > halfLen && s[0] == 0.toByte()) s.copyOfRange(1, s.size) else s

        System.arraycopy(rTrimmed, 0, jose, halfLen - rTrimmed.size, rTrimmed.size)
        System.arraycopy(sTrimmed, 0, jose, joseLen - sTrimmed.size, sTrimmed.size)
        return jose
    }
}
