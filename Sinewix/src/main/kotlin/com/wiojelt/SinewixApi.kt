package com.wiojelt

import android.util.Base64
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.Semaphore

object SinewixApi {
    const val SITE = "https://ydfvfdizipanel.ru"
    const val API_KEY = "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA"
    private const val PKG = "com.wxsmash.game"
    private const val APP_ID = "sinewix-mobile"
    private const val APP_VER = "2.5.3"
    private const val HASH256 = "f4d4bc98a3fc4600e7f2c2bab7533f1f03d8a70ff03c256bb11dc57050536bd0"
    private const val CERT_HEX = "308202c3308201aca003020102021430030f5763077759b8be9be1c54b2d35c249457a300a06082a8648ce3d0403023053310b3009060355040613025452310f300d060355040813065475726b657931"
    private const val SIGN_KEY = "4962QZiZZqWbQlC0k0pdIvojy-STtR8NTq7oQCxMGBF2kwsVX2DBxgK72BovMy4U"
    private const val RESP_KEY = "H1KKG-JCt1RqHQawNxw7xujy2-vevYuyBqX3Aei1EAdi9VwDnmRL-3Oz2xxvy1VU"
    const val API_UA = "EasyPlex (Android 14; SM-A546B; Samsung Galaxy A54 5G; tr)"
    const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private var jwt = ""
    private var session = ""
    private var exp = 0L
    private val device = "uuid_" + UUID.randomUUID().toString()

    // Ana sayfa ayni anda birden cok satir yukler. Her istek ayri giris yapip
    // birbirinin oturumunu bozuyordu; bu yuzden istekler sirayla yapilir.
    private val lock = Semaphore(1)

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(msg)
    }
    private fun rnd(): String {
        val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
    fun b64(s: String): ByteArray =
        Base64.decode(s.replace('-', '+').replace('_', '/'), Base64.DEFAULT)

    private fun sign(method: String, path: String, ts: String, nonce: String, body: String): String {
        val canon = "$method\n$path\n$ts\n$nonce\n" + sha256(body.toByteArray()).hex()
        return hmac(SIGN_KEY.toByteArray(), canon.toByteArray()).hex()
    }

    private fun headers(ts: String, nonce: String, sig: String): MutableMap<String, String> =
        mutableMapOf(
            "Accept" to "application/json", "packagename" to PKG, "User-Agent" to API_UA,
            "X-App-Id" to APP_ID, "X-Platform" to "android", "X-Device-Id" to device,
            "signature" to CERT_HEX, "hash256" to HASH256, "X-Encrypt-Response" to "1",
            "X-Timestamp" to ts, "X-Nonce" to nonce, "X-Signature" to "sha256=$sig"
        )

    private fun decrypt(env: JSONObject, nonce: String, ts: String): String? {
        val iv = b64(env.optString("iv")); val tag = b64(env.optString("tag"))
        val aad = b64(env.optString("aad")); val ct = b64(env.optString("ciphertext"))
        val skip = setOf("iv", "tag", "aad", "ciphertext")
        val extra = env.keys().asSequence().filter { it !in skip }
            .mapNotNull { env.opt(it) as? String }.toList()
        val ids = (listOf(session, device, "") + extra).distinct()
        val pairs = mutableListOf(nonce to ts, ts to nonce)
        extra.forEach { pairs += it to ts; pairs += nonce to it; pairs += ts to it }
        for (id in ids) for (p in pairs) {
            val key = hmac(RESP_KEY.toByteArray(), "easyplex-response-v1|$id|${p.first}|${p.second}".toByteArray())
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tag.size * 8, iv))
                if (aad.isNotEmpty()) c.updateAAD(aad)
                return String(c.doFinal(ct + tag), Charsets.UTF_8)
            } catch (_: Exception) { }
        }
        return null
    }

    private fun open(text: String, nonce: String, ts: String): Any? {
        if (text.isBlank()) return null
        val v = try { JSONTokener(text).nextValue() } catch (_: Exception) { return null }
        if (v is JSONObject && v.optBoolean("encrypted") && v.has("ciphertext")) {
            val d = decrypt(v, nonce, ts) ?: return null
            return try { JSONTokener(d).nextValue() } catch (_: Exception) { null }
        }
        return v
    }

    private suspend fun login(): Boolean {
        if (jwt.isNotEmpty() && System.currentTimeMillis() < exp) return true
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = rnd()
        val body = listOf(
            "signature" to HASH256, "package_name" to PKG, "device_id" to device,
            "nonce" to rnd(), "timestamp" to ts, "app_version" to APP_VER,
            "app_id" to APP_ID, "platform" to "android"
        ).joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }
        val h = headers(ts, nonce, sign("POST", "/api/tokens/mobile/generate", ts, nonce, body))
        val r = app.post(
            "$SITE/public/api/tokens/mobile/generate", headers = h,
            requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        )
        val o = open(r.text, nonce, ts) as? JSONObject ?: return false
        val tok = o.optString("access_token")
        if (tok.isEmpty()) return false
        jwt = tok
        session = o.optString("session_id")
        exp = System.currentTimeMillis() + maxOf(60, o.optInt("expires_in", 600) - 30) * 1000L
        return true
    }

    private suspend fun getOnce(path: String): Any? {
        if (!login()) return null
        val url = when {
            path.startsWith("http") -> path
            path.startsWith("/public/api/") -> SITE + path
            else -> SITE + "/public/api/" + path.trimStart('/')
        }
        val i = url.substringBefore("?").indexOf("/api/")
        val apiPath = if (i >= 0) url.substringBefore("?").substring(i) else url
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = rnd()
        val h = headers(ts, nonce, sign("GET", apiPath, ts, nonce, ""))
        if (session.isNotEmpty()) h["X-Session-Id"] = session
        h["Authorization"] = "Bearer $jwt"
        val r = app.get(url, headers = h)
        if (r.code == 401 || r.code == 403) { jwt = ""; return null }
        return open(r.text, nonce, ts)
    }

    suspend fun get(path: String): Any? {
        lock.acquire()
        try {
            var res = getOnce(path)
            if (res == null) {
                jwt = ""
                res = getOnce(path)
            }
            return res
        } finally {
            lock.release()
        }
    }

    fun items(o: Any?): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun take(a: JSONArray) {
            for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.has("id") }?.let { out += it }
        }
        when (o) {
            is JSONArray -> take(o)
            is JSONObject -> o.keys().forEach { k -> (o.opt(k) as? JSONArray)?.let { take(it) } }
        }
        return out
    }
}
