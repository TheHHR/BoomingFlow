package com.mardous.booming.data.remote.lyrics.spatial.security

import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.EllipticCurve

/**
 * Generates self-signed ES256 JWT authorization bearer tokens used on
 * signed BetterLyrics (`lyrics-api.boidu.dev`) requests:
 * `Authorization: Bearer <token>` + `X-Key-Id: <device keyId>`.
 *
 * Ported from SpatialFlow. The claims/key below belong to the original
 * app - they are only kept as a fallback issuer; the primary identity
 * comes from [DeviceKeyIdentityManager].
 */
object JwtTokenSigner {
    private const val TAG = "JwtTokenSigner"

    private const val KEY_ID = "27f1baa3b8edc3851900ba513f44a9728808a1291880af31db13bf74c908fb47"
    private const val PRIVATE_D_B64 = "ay13Mko5WCaZ3syBdHU5Hmj5lMgdZPvlddFmHRdGuU8"

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiryMs: Long = 0L

    fun getOrGenerateBearerToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiryMs - 60_000) {
            return cachedToken
        }
        val newToken = generateEs256Jwt()
        if (newToken != null) {
            cachedToken = newToken
            tokenExpiryMs = now + 86400_000L
        }
        return newToken
    }

    private fun generateEs256Jwt(): String? {
        return try {
            val dBytes = Base64.decode(
                PRIVATE_D_B64,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            val dInt = BigInteger(1, dBytes)

            val kf = KeyFactory.getInstance("EC")
            val ecParamSpec = getSecp256r1Params()
            val privKeySpec = ECPrivateKeySpec(dInt, ecParamSpec)
            val privateKey = kf.generatePrivate(privKeySpec)

            val nowSec = System.currentTimeMillis() / 1000
            val expSec = nowSec + (86400 * 180) // 180 days validity

            val headerJson = """{"alg":"ES256","kid":"$KEY_ID","typ":"JWT"}"""
            val payloadJson = """{"iss":"BoomingFlow","iat":$nowSec,"exp":$expSec}"""

            val headerB64 = base64UrlEncode(headerJson.toByteArray(StandardCharsets.UTF_8))
            val payloadB64 = base64UrlEncode(payloadJson.toByteArray(StandardCharsets.UTF_8))
            val unsignedToken = "$headerB64.$payloadB64"

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(unsignedToken.toByteArray(StandardCharsets.UTF_8))
            val derSig = signature.sign()

            val joseSig = derToJose(derSig, 64)
            val sigB64 = base64UrlEncode(joseSig)

            "$unsignedToken.$sigB64"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate ES256 JWT token", e)
            null
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
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

        val rClean = if (r.size > halfLen && r[0] == 0.toByte()) r.copyOfRange(1, r.size) else r
        val sClean = if (s.size > halfLen && s[0] == 0.toByte()) s.copyOfRange(1, s.size) else s

        System.arraycopy(rClean, 0, jose, halfLen - rClean.size, rClean.size)
        System.arraycopy(sClean, 0, jose, joseLen - sClean.size, sClean.size)
        return jose
    }

    private fun getSecp256r1Params(): ECParameterSpec {
        val p = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        val a = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16)
        val b = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
        val gx = BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16)
        val gy = BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
        val n = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        val h = 1

        val curve = EllipticCurve(ECFieldFp(p), a, b)
        val g = ECPoint(gx, gy)
        return ECParameterSpec(curve, g, n, h)
    }
}
