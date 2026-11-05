package com.bymesut1

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RecTV : MainAPI() {

    override var mainUrl = "https://a.prectv71.lol"
    override var name = "RecTV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Live,
        TvType.TvSeries
    )

    companion object {
        private const val USER_AGENT = "googleusercontent"
        private const val REFERER = "https://twitter.com/"
        private const val APP_VERSION = "157"
        private const val CLIENT_ID = "rectv-android"

        private const val SW_KEY =
            "4F5A9C3D9A86FA54EACEDDD635185/c3c5bd17-e37b-4b94-a944-8a3688a30452"

        private const val HMAC_KEY =
            "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"

        private const val AES_KEY_HEX =
            "666482389dc76bfa57068407418f7dac9f6c14b6868856b169165b9fac7d812e"

        private const val FIREBASE_REMOTE_CONFIG_URL =
            "https://firebaseremoteconfig.googleapis.com/v1/projects/791583031279/namespaces/firebase:fetch"

        private const val FIREBASE_API_KEY =
            "AIzaSyBbhpzG8Ecohu9yArfCO5tF13BQLhjLahc"

        private const val FIREBASE_ANDROID_PACKAGE =
            "com.rectv.shot"

        private const val FIREBASE_APP_ID =
            "1:791583031279:android:1"

        private const val FIREBASE_APP_BUILD =
            "81"

        private const val FIREBASE_APP_INSTANCE_ID =
            "evON8ZdeSr-0wUYxf0qs68"
    }

    @Volatile
    private var cachedJwt: String? = null

    @Volatile
    private var jwtExpirationTimestamp: Long = 0L

    @Volatile
    private var cachedApiBaseUrl: String? = null

    // =========================================================
    // API BASE URL
    // =========================================================

    private suspend fun getApiBaseUrl(): String {

        cachedApiBaseUrl?.let {
            return it
        }

        return try {

            val body =
                JSONObject()
                    .put("appBuild", FIREBASE_APP_BUILD)
                    .put("appInstanceId", FIREBASE_APP_INSTANCE_ID)
                    .put("appId", FIREBASE_APP_ID)
                    .toString()

            val headers =
                mapOf(
                    "X-Goog-Api-Key" to FIREBASE_API_KEY,
                    "X-Android-Package" to FIREBASE_ANDROID_PACKAGE,
                    "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 12)"
                )

            val response =
                app.post(
                    FIREBASE_REMOTE_CONFIG_URL,
                    headers = headers,
                    requestBody = body.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )

            val json =
                try {
                    JSONObject(response.text)
                } catch (_: Exception) {
                    null
                }

            var apiUrl =
                json
                    ?.optJSONObject("entries")
                    ?.optString("api_url")
                    ?.trim()
                    .orEmpty()

            if (apiUrl.isBlank()) {
                return mainUrl
            }

            apiUrl = apiUrl.removeSuffix("/")

            while (apiUrl.endsWith("/api")) {
                apiUrl = apiUrl.removeSuffix("/api")
            }

            if (
                !apiUrl.startsWith("http://") &&
                !apiUrl.startsWith("https://")
            ) {
                return mainUrl
            }

            cachedApiBaseUrl = apiUrl
            mainUrl = apiUrl

            Log.d(
                "RecTV",
                "DYNAMIC API BASE: $apiUrl"
            )

            apiUrl

        } catch (e: Exception) {

            Log.e(
                "RecTV",
                "Remote Config failed: ${e.message}",
                e
            )

            mainUrl
        }
    }

    // =========================================================
    // CRYPTO / SIGNATURE
    // =========================================================

    private fun sha256Hex(data: String): String {

        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(data.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }

    private fun hmacSha256Hex(
        key: String,
        message: String
    ): String {

        val mac =
            Mac.getInstance("HmacSHA256")

        mac.init(
            SecretKeySpec(
                key.toByteArray(Charsets.UTF_8),
                "HmacSHA256"
            )
        )

        return mac.doFinal(
            message.toByteArray(Charsets.UTF_8)
        ).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {

        val output = ByteArray(hex.length / 2)

        for (index in output.indices) {
            val offset = index * 2
            output[index] =
                hex.substring(offset, offset + 2)
                    .toInt(16)
                    .toByte()
        }

        return output
    }

    // =========================================================
    // JWT
    // =========================================================

    private suspend fun getJwt(): String? {

        val now =
            System.currentTimeMillis() / 1000L

        cachedJwt?.let { token ->
            if (now < jwtExpirationTimestamp - 300) {
                return token
            }
        }

        return try {

            val currentNow =
                System.currentTimeMillis() / 1000L

            val recheckJwt = cachedJwt

            if (
                recheckJwt != null &&
                currentNow < jwtExpirationTimestamp - 300
            ) {
                return recheckJwt
            }

            val apiBase = getApiBaseUrl()
            val path = "/api/attest/verify"
            val body = "{}"

            val headers =
                getSignedHeaders(
                    method = "POST",
                    path = path,
                    body = body,
                    includeAuth = false
                ).toMutableMap()

            headers["Content-Type"] = "application/json"

            val response =
                app.post(
                    "$apiBase$path",
                    headers = headers,
                    requestBody = body.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )

            val json =
                try {
                    JSONObject(response.text)
                } catch (_: Exception) {
                    null
                }

            val token =
                json?.optString("jwt")?.takeIf {
                    it.isNotBlank()
                }

            if (token != null) {

                cachedJwt = token

                var expiration =
                    json.optLong(
                        "exp",
                        currentNow + 7000L
                    )

                try {

                    val parts = token.split(".")

                    if (parts.size >= 2) {

                        val payloadBytes =
                            Base64.decode(
                                parts[1],
                                Base64.URL_SAFE or
                                    Base64.NO_PADDING or
                                    Base64.NO_WRAP
                            )

                        val payloadJson =
                            String(
                                payloadBytes,
                                Charsets.UTF_8
                            )

                        Regex(
                            "\"exp\"\\s*:\\s*(\\d+)"
                        ).find(payloadJson)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toLongOrNull()
                            ?.let {
                                expiration = it
                            }
                    }

                } catch (_: Exception) {
                }

                jwtExpirationTimestamp = expiration
                token

            } else {
                cachedJwt
            }

        } catch (e: Exception) {

            Log.e(
                "RecTV",
                "Failed to fetch JWT: ${e.message}",
                e
            )

            cachedJwt
        }
    }

    // =========================================================
    // SIGNED HEADERS
    // =========================================================

    private suspend fun getSignedHeaders(
        method: String,
        path: String,
        body: String = "",
        includeAuth: Boolean = true
    ): Map<String, String> {

        val timestamp =
            (System.currentTimeMillis() / 1000L).toString()

        val nonce = UUID.randomUUID().toString()
        val bodyHash = sha256Hex(body)

        val message =
            "$method\n$path\n$timestamp\n$nonce\n$bodyHash"

        val signature =
            hmacSha256Hex(
                HMAC_KEY,
                message
            )

        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to REFERER,
            "Accept" to "application/json",
            "X-Timestamp" to timestamp,
            "X-Nonce" to nonce,
            "X-HMAC" to signature,
            "X-Signature" to signature,
            "X-App-Version" to APP_VERSION,
            "X-Client-Id" to CLIENT_ID
        )

        if (includeAuth) {
            val token = getJwt()

            if (!token.isNullOrBlank()) {
                headers["Authorization"] = "Bearer $token"
            }
        }

        return headers
    }

    // =========================================================
    // STREAM URL DECRYPTION
    // =========================================================

    private fun decryptEncUrl(
        encUrl: String
    ): String? {

        return try {

            val raw =
                Base64.decode(
                    encUrl,
                    Base64.DEFAULT
                )

            if (raw.size < 28) {
                return null
            }

            val iv = raw.copyOfRange(0, 12)
            val cipherTextAndTag = raw.copyOfRange(12, raw.size)

            val cipher =
                Cipher.getInstance(
                    "AES/GCM/NoPadding"
                )

            val keySpec =
                SecretKeySpec(
                    hexToBytes(AES_KEY_HEX),
                    "AES"
                )

            val gcmSpec =
                GCMParameterSpec(
                    128,
                    iv
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                gcmSpec
            )

            String(
                cipher.doFinal(cipherTextAndTag),
                Charsets.UTF_8
            )

        } catch (e: Exception) {

            Log.e(
                "RecTV",
                "Failed to decrypt URL: ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // SOURCE UNLOCK
    // =========================================================

    private suspend fun unlockSource(
        sourceId: Int
    ): String? {

        return try {

            val apiBase = getApiBaseUrl()
            val path =
                "/api/source/unlock-ad/$sourceId/$SW_KEY/"
            val body = "{}"

            val headers =
                getSignedHeaders(
                    method = "POST",
                    path = path,
                    body = body
                ).toMutableMap()

            headers["Content-Type"] = "application/json"

            val response =
                app.post(
                    "$apiBase$path",
                    headers = headers,
                    requestBody = body.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )

            JSONObject(response.text)
                .optString("enc_url")
                .takeIf { it.isNotBlank() }

        } catch (e: Exception) {

            Log.e(
                "RecTV",
                "Failed to unlock source $sourceId: ${e.message}"
            )

            null
        }
    }

    // =========================================================
    // JSON HELPERS
    // =========================================================

    private fun parseObject(text: String): JSONObject? =
        try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }

    private fun parseArray(text: String): JSONArray? =
        try {
            JSONArray(text)
        } catch (_: Exception) {
            null
        }

    private fun optStringOrNull(
        obj: JSONObject,
        vararg keys: String
    ): String? {

        for (key in keys) {

            if (!obj.has(key)) continue

            val value = obj.opt(key)

            if (
                value == null ||
                value == JSONObject.NULL
            ) continue

            val text = value.toString().trim()

            if (text.isNotBlank()) {
                return text
            }
        }

        return null
    }

    private fun optIntOrNull(
        obj: JSONObject,
        vararg keys: String
    ): Int? {

        for (key in keys) {

            if (!obj.has(key)) continue

            when (val value = obj.opt(key)) {
                is Number -> return value.toInt()
                is String ->
                    value.trim().toIntOrNull()?.let {
                        return it
                    }
            }
        }

        return null
    }

    private fun optBooleanOrNull(
        obj: JSONObject,
        vararg keys: String
    ): Boolean? {

        for (key in keys) {

            if (!obj.has(key)) continue

            when (val value = obj.opt(key)) {
                is Boolean -> return value
                is String -> when (value.trim().lowercase()) {
                    "true", "1", "yes" -> return true
                    "false", "0", "no" -> return false
                }
            }
        }

        return null
    }

    private fun optDoubleOrNull(
        obj: JSONObject,
        vararg keys: String
    ): Double? {

        for (key in keys) {

            if (!obj.has(key)) continue

            when (val value = obj.opt(key)) {
                is Number -> return value.toDouble()
                is String -> {
                    value
                        .replace(",", ".")
                        .trim()
                        .toDoubleOrNull()
                        ?.let { return it }
                }
            }
        }

        return null
    }

    private fun optArray(
        obj: JSONObject,
        vararg keys: String
    ): JSONArray? {

        for (key in keys) {
            obj.optJSONArray(key)?.let { return it }
        }

        return null
    }

    private fun hasArrayItems(
        obj: JSONObject,
        vararg keys: String
    ): Boolean =
        optArray(obj, *keys)?.length()?.let {
            it > 0
        } ?: false

    private fun objectToString(
        obj: JSONObject
    ): String = obj.toString()

    // =========================================================
    // FLEXIBLE ITEM EXTRACTION
    // =========================================================

    private fun extractItemObjects(
        text: String
    ): List<JSONObject> {

        val result = mutableListOf<JSONObject>()

        fun addArray(array: JSONArray?) {

            if (array == null) return

            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let {
                    result += it
                }
            }
        }

        try {

            addArray(JSONArray(text))

            if (result.isNotEmpty()) {
                return result
            }

        } catch (_: Exception) {
        }

        try {

            val root = JSONObject(text)

            val priorityKeys = listOf(
                "data",
                "results",
                "items",
                "movies",
                "series",
                "channels",
                "posters",
                "contents",
                "records",
                "rows",
                "list"
            )

            for (key in priorityKeys) {

                addArray(root.optJSONArray(key))

                if (result.isNotEmpty()) {
                    return result
                }
            }

            for (key in priorityKeys) {

                root.optJSONObject(key)?.let { child ->
                    val nested =
                        extractItemObjects(child.toString())

                    if (nested.isNotEmpty()) {
                        return nested
                    }
                }
            }

        } catch (_: Exception) {
        }

        return result
    }

    // =========================================================
    // IMAGE HELPERS
    // =========================================================

    private fun normalizeImageUrl(
        value: String?
    ): String? {

        val url =
            value
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

        return when {
            url.startsWith("http://") ||
                url.startsWith("https://") -> url

            url.startsWith("//") ->
                "https:$url"

            url.startsWith("/") ->
                "$mainUrl$url"

            else ->
                "$mainUrl/$url"
        }
    }

    private fun optImageUrl(
        obj: JSONObject
    ): String? {

        val simpleKeys = listOf(
            "image",
            "poster",
            "poster_url",
            "posterUrl",
            "thumbnail",
            "thumbnail_url",
            "thumbnailUrl",
            "thumb",
            "cover",
            "cover_url",
            "coverUrl",
            "image_url",
            "imageUrl"
        )

        for (key in simpleKeys) {

            when (val value = obj.opt(key)) {

                is String -> {
                    normalizeImageUrl(value)?.let {
                        return it
                    }
                }

                is JSONObject -> {

                    val nested =
                        optStringOrNull(
                            value,
                            "url",
                            "src",
                            "image",
                            "poster",
                            "path",
                            "original",
                            "medium",
                            "large"
                        )

                    normalizeImageUrl(nested)?.let {
                        return it
                    }
                }
            }
        }

        obj.optJSONObject("images")?.let { images ->

            val nested =
                optStringOrNull(
                    images,
                    "poster",
                    "poster_url",
                    "cover",
                    "image",
                    "url",
                    "src"
                )

            normalizeImageUrl(nested)?.let {
                return it
            }
        }

        return null
    }

    // =========================================================
    // METADATA HELPERS
    // =========================================================

    private fun parseScore(
        obj: JSONObject
    ): Score? {

        // IMPORTANT:
        // Do not treat generic fields such as `rating`, `score` or
        // `vote_average` as IMDb. RecTV may use those for its own score.
        // Only explicitly IMDb-labelled numeric fields are accepted.

        var value =
            optDoubleOrNull(
                obj,
                "imdb_rating",
                "imdbRating",
                "imdb_score",
                "imdbScore",
                "imdb_value",
                "imdbValue",
                "imdb_rate",
                "imdbRate"
            )

        // Some APIs expose IMDb as a numeric `imdb` field.
        if (value == null && obj.has("imdb")) {
            val imdbValue = obj.opt("imdb")

            when (imdbValue) {
                is Number -> {
                    value = imdbValue.toDouble()
                }

                is String -> {
                    val text = imdbValue.trim()

                    // IMDb ids such as tt1234567 are NOT ratings.
                    if (!text.startsWith("tt", ignoreCase = true)) {
                        value = text.toDoubleOrNull()
                    }
                }

                is JSONObject -> {
                    value =
                        optDoubleOrNull(
                            imdbValue,
                            "rating",
                            "score",
                            "value",
                            "aggregateRating"
                        )
                }
            }
        }

        // Nested IMDb rating object.
        if (value == null) {
            obj.optJSONObject("ratings")?.let { ratings ->
                value =
                    optDoubleOrNull(
                        ratings,
                        "imdb_rating",
                        "imdbRating",
                        "imdb_score",
                        "imdbScore",
                        "imdb_value",
                        "imdbValue"
                    )

                if (value == null) {
                    val imdb = ratings.opt("imdb")

                    when (imdb) {
                        is Number -> {
                            value = imdb.toDouble()
                        }

                        is JSONObject -> {
                            value =
                                optDoubleOrNull(
                                    imdb,
                                    "rating",
                                    "score",
                                    "value",
                                    "aggregateRating"
                                )
                        }
                    }
                }
            }
        }

        if (value == null) {
            return null
        }

        // Normalize common rating scales to 0..10.
        val normalized =
            when {
                value!! in 0.0..10.0 ->
                    value!!

                value!! > 10.0 && value!! <= 100.0 ->
                    value!! / 10.0

                value!! > 100.0 && value!! <= 1000.0 ->
                    value!! / 100.0

                else ->
                    return null
            }

        return try {
            Score.from10(
                normalized.coerceIn(0.0, 10.0)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseImdbId(
        obj: JSONObject
    ): String? {

        val direct =
            optStringOrNull(
                obj,
                "imdb_id",
                "imdbId",
                "imdbid",
                "imdb_code"
            )

        if (
            !direct.isNullOrBlank() &&
            direct.startsWith("tt")
        ) {
            return direct
        }

        val imdbValue =
            optStringOrNull(
                obj,
                "imdb"
            )

        if (
            !imdbValue.isNullOrBlank() &&
            imdbValue.startsWith("tt")
        ) {
            return imdbValue
        }

        return null
    }

    private fun parseActors(
        obj: JSONObject
    ): List<ActorData>? {

        val result = mutableListOf<ActorData>()

        fun addActor(
            value: Any?
        ) {

            when (value) {

                is String -> {

                    val name = value.trim()

                    if (name.isNotBlank()) {
                        result += ActorData(
                            Actor(name)
                        )
                    }
                }

                is JSONObject -> {

                    val name =
                        optStringOrNull(
                            value,
                            "name",
                            "title",
                            "actor",
                            "character_name",
                            "character"
                        ) ?: return

                    val image =
                        optStringOrNull(
                            value,
                            "image",
                            "image_url",
                            "imageUrl",
                            "photo",
                            "photo_url",
                            "profile_path",
                            "profile",
                            "avatar",
                            "poster"
                        )

                    result += ActorData(
                        Actor(
                            name = name,
                            image = normalizeImageUrl(image)
                        )
                    )
                }
            }
        }

        val arrays = listOf(
            "actors",
            "cast",
            "credits",
            "crew"
        )

        for (key in arrays) {

            val array = obj.optJSONArray(key)
                ?: continue

            for (index in 0 until array.length()) {
                addActor(array.opt(index))
            }

            if (result.isNotEmpty() && key != "crew") {
                break
            }
        }

        if (result.isEmpty()) {

            obj.optJSONObject("credits")?.let { credits ->
                credits.optJSONArray("cast")?.let { cast ->
                    for (index in 0 until cast.length()) {
                        addActor(cast.opt(index))
                    }
                }
            }
        }

        return result
            .distinctBy {
                it.actor.name.lowercase()
            }
            .take(30)
            .takeIf {
                it.isNotEmpty()
            }
    }

    private fun parseTrailerUrls(
        obj: JSONObject
    ): List<String> {

        val result = mutableListOf<String>()

        fun addUrl(value: Any?) {

            when (value) {

                is String -> {
                    val url = value.trim()

                    if (
                        url.startsWith("http://") ||
                        url.startsWith("https://")
                    ) {
                        result += url
                    }
                }

                is JSONObject -> {

                    val url =
                        optStringOrNull(
                            value,
                            "url",
                            "link",
                            "src",
                            "href",
                            "trailer_url",
                            "trailerUrl",
                            "youtube",
                            "youtube_url",
                            "youtubeUrl",
                            "key"
                        )

                    addUrl(url)
                }
            }
        }

        val directKeys = listOf(
            "trailer",
            "trailer_url",
            "trailerUrl",
            "trailer_link",
            "trailerLink",
            "youtube_trailer",
            "youtube_url",
            "youtubeUrl"
        )

        for (key in directKeys) {
            addUrl(obj.opt(key))
        }

        listOf(
            "trailers",
            "videos",
            "video"
        ).forEach { key ->

            when (val value = obj.opt(key)) {

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        addUrl(value.opt(index))
                    }
                }

                is JSONObject -> {
                    addUrl(value)
                }
            }
        }

        return result
            .map { it.trim() }
            .distinct()
            .take(5)
    }

    private fun parseBackgroundUrl(
        obj: JSONObject
    ): String? {

        val direct =
            optStringOrNull(
                obj,
                "background",
                "background_url",
                "backgroundUrl",
                "backdrop",
                "backdrop_url",
                "backdropUrl",
                "backdrop_path",
                "fanart",
                "fanart_url",
                "cover_background"
            )

        if (direct != null) {
            return normalizeImageUrl(direct)
        }

        obj.optJSONObject("images")?.let { images ->

            val nested =
                optStringOrNull(
                    images,
                    "background",
                    "backdrop",
                    "backdrop_url",
                    "fanart",
                    "cover"
                )

            normalizeImageUrl(nested)?.let {
                return it
            }
        }

        return null
    }

    private fun applyMetadata(
        response: LoadResponse,
        data: JSONObject
    ) {

        // CloudStream sürüm bağımsız olarak doğrudan model alanlarını
        // kullanıyoruz. addScore/addActors/addTrailer uzantıları bazı
        // eski prerelease sürümlerinde bulunmuyor.
        response.score =
            parseScore(data)

        response.actors =
            parseActors(data)

        parseImdbId(data)?.let { imdbId ->
            response.syncData["imdb_id"] = imdbId
            response.syncData["imdb"] = imdbId
        }

        parseBackgroundUrl(data)?.let { background ->
            response.backgroundPosterUrl =
                background
        }

        val trailers =
            parseTrailerUrls(data)

        if (trailers.isNotEmpty()) {
            response.trailers.clear()

            trailers.forEach { trailerUrl ->
                response.trailers +=
                    TrailerData(
                        extractorUrl = trailerUrl,
                        referer = null,
                        raw = true
                    )
            }
        }
    }

    // =========================================================
    // TYPE
    // =========================================================

    private fun detectItemType(
        obj: JSONObject
    ): String {

        val explicit =
            optStringOrNull(
                obj,
                "type",
                "content_type",
                "contentType",
                "kind",
                "model_type",
                "modelType",
                "_rectv_type"
            )
                ?.lowercase()
                .orEmpty()

        return when {

            explicit.contains("live") ||
                explicit.contains("channel") ||
                explicit.contains("sport") ||
                explicit.contains("canli") ->
                "live"

            explicit.contains("serie") ||
                explicit.contains("series") ||
                explicit.contains("tvshow") ||
                explicit == "tv" ||
                explicit.contains("dizi") ->
                "serie"

            else ->
                "movie"
        }
    }

    // =========================================================
    // MAIN PAGE
    // =========================================================

    override val mainPage =
        mainPageOf(
            "channel|1|0" to "Spor",
            "channel|0|0" to "Canlı TV",
            "movie|0|created" to "Son Filmler",
            "serie|0|created" to "Son Diziler",
            "movie|14|created" to "Aile",
            "movie|1|created" to "Aksiyon",
            "movie|13|created" to "Animasyon",
            "movie|19|created" to "Belgesel",
            "movie|4|created" to "Bilim Kurgu",
            "movie|2|created" to "Dram",
            "movie|10|created" to "Fantastik",
            "movie|3|created" to "Komedi",
            "movie|8|created" to "Korku",
            "movie|17|created" to "Macera",
            "movie|5|created" to "Romantik"
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val apiBase = getApiBaseUrl()
        val pageIndex = page - 1
        val parts = request.data.split("|")

        if (parts.size < 3) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val contentType = parts[0]
        val filter = parts[1]
        val sort = parts[2]

        val endpoint =
            when (contentType) {

                "channel" ->
                    "$apiBase/api/channel/by/filtres/$filter/$sort/$pageIndex/$SW_KEY/"

                "movie" ->
                    "$apiBase/api/movie/by/filtres/$filter/$sort/$pageIndex/$SW_KEY/"

                "serie" ->
                    "$apiBase/api/serie/by/filtres/$filter/$sort/$pageIndex/$SW_KEY/"

                else ->
                    return newHomePageResponse(
                        request.name,
                        emptyList(),
                        hasNext = false
                    )
            }

        val path =
            try {
                java.net.URI(endpoint).rawPath
            } catch (_: Exception) {
                endpoint.substringBefore("?")
            }

        Log.d(
            "RecTV",
            "MAIN REQUEST: $endpoint"
        )

        val response =
            try {
                app.get(
                    endpoint,
                    headers = getSignedHeaders(
                        method = "GET",
                        path = path
                    )
                )
            } catch (e: Exception) {
                Log.e(
                    "RecTV",
                    "Main page request failed: ${e.message}",
                    e
                )

                return newHomePageResponse(
                    request.name,
                    emptyList(),
                    hasNext = false
                )
            }

        Log.d(
            "RecTV",
            "MAIN HTTP=${response.code}"
        )

        Log.d(
            "RecTV",
            "MAIN BODY: ${response.text.take(6000)}"
        )

        if (
            response.text.contains(
                "\"missing_hmac\"",
                ignoreCase = true
            )
        ) {
            Log.e(
                "RecTV",
                "SERVER STILL REPORTS missing_hmac"
            )

            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val objects =
            extractItemObjects(response.text)

        Log.d(
            "RecTV",
            "MAIN OBJECT COUNT=${objects.size}"
        )

        if (objects.isEmpty()) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val results = mutableListOf<SearchResponse>()

        for (item in objects) {

            val id =
                optIntOrNull(
                    item,
                    "id",
                    "movie_id",
                    "serie_id",
                    "channel_id"
                ) ?: 0

            val title =
                optStringOrNull(
                    item,
                    "title",
                    "name",
                    "movie_name",
                    "serie_name",
                    "channel_name"
                ) ?: continue

            val image = optImageUrl(item)
            val detectedType = detectItemType(item)

            val label =
                optStringOrNull(
                    item,
                    "label",
                    "status"
                ).orEmpty()

            val forcedLive =
                contentType == "channel" ||
                    label.equals(
                        "CANLI",
                        ignoreCase = true
                    )

            if (id > 0) {
                item.put(
                    "_rectv_id",
                    id
                )
            }

            item.put(
                "_rectv_type",
                contentType
            )

            item.put(
                "_rectv_section",
                request.name
            )

            val data = item.toString()
            val score = parseScore(item)

            when {

                forcedLive || detectedType == "live" -> {
                    results +=
                        newLiveSearchResponse(
                            title,
                            data,
                            TvType.Live
                        ) {
                            this.posterUrl = image
                            this.score = score
                        }
                }

                contentType == "serie" ||
                    detectedType == "serie" -> {
                    results +=
                        newTvSeriesSearchResponse(
                            title,
                            data,
                            TvType.TvSeries
                        ) {
                            this.posterUrl = image
                            this.score = score
                        }
                }

                else -> {
                    results +=
                        newMovieSearchResponse(
                            title,
                            data,
                            TvType.Movie
                        ) {
                            this.posterUrl = image
                            this.score = score
                        }
                }
            }

            if (results.size >= 24) {
                break
            }
        }

        Log.d(
            "RecTV",
            "MAIN RESULTS=${results.size}"
        )

        return newHomePageResponse(
            request.name,
            results,
            hasNext = objects.size >= 24
        )
    }

    // =========================================================
    // SEARCH
    // =========================================================

    private fun matchesSearchQuery(query: String, title: String): Boolean {
        fun normalize(value: String): String {
            val lowered = value.lowercase(java.util.Locale.ROOT).replace('ı', 'i')
            val decomposed = java.text.Normalizer.normalize(lowered, java.text.Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
        }
        val q = normalize(query)
        val t = normalize(title)
        if (q.isBlank() || t.isBlank()) return false
        val ignored = setOf("the", "a", "an", "of", "and", "or", "ve")
        val year = Regex("(?:18|19|20|21)\\d{2}")
        val words = q.split(' ').filterNot { it in ignored || year.matches(it) }
        if (words.isEmpty()) return q == t
        val titleWords = t.split(' ').toSet()
        val numberWords = mapOf("0" to "zero", "1" to "one", "2" to "two", "3" to "three",
            "4" to "four", "5" to "five", "6" to "six", "7" to "seven", "8" to "eight",
            "9" to "nine", "10" to "ten")
        return words.all { word ->
            val mapped = numberWords[word]
            titleWords.any { candidate ->
                candidate == word || (mapped != null && candidate == mapped) ||
                    (candidate.length >= 4 && word.length >= 4 &&
                        kotlin.math.abs(candidate.length - word.length) <= 2 &&
                        (candidate.startsWith(word) || word.startsWith(candidate)))
            }
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val apiBase = getApiBaseUrl()

        val encoded =
            URLEncoder
                .encode(query, "UTF-8")
                .replace("+", "%20")

        val path = "/api/search/$encoded/$SW_KEY/"

        val response =
            try {
                app.get(
                    "$apiBase$path",
                    headers = getSignedHeaders(
                        method = "GET",
                        path = path
                    )
                )
            } catch (e: Exception) {
                Log.e(
                    "RecTV",
                    "Search failed: ${e.message}"
                )
                return emptyList()
            }

        val results = mutableListOf<SearchResponse>()
        val json = parseObject(response.text)

        if (json == null) {

            for (item in extractItemObjects(response.text)) {

                val title =
                    optStringOrNull(
                        item,
                        "title",
                        "name"
                    ) ?: continue

                val image = optImageUrl(item)
                val score = parseScore(item)

                when (detectItemType(item)) {

                    "live" -> {
                        results +=
                            newLiveSearchResponse(
                                title,
                                item.toString(),
                                TvType.Live
                            ) {
                                this.posterUrl = image
                                this.score = score
                            }
                    }

                    "serie" -> {
                        results +=
                            newTvSeriesSearchResponse(
                                title,
                                item.toString(),
                                TvType.TvSeries
                            ) {
                                this.posterUrl = image
                                this.score = score
                            }
                    }

                    else -> {
                        results +=
                            newMovieSearchResponse(
                                title,
                                item.toString(),
                                TvType.Movie
                            ) {
                                this.posterUrl = image
                                this.score = score
                            }
                    }
                }
            }

            return results.filter { matchesSearchQuery(query, it.name) }
        }

        optArray(json, "channels")?.let { channels ->

            for (index in 0 until channels.length()) {

                val item =
                    channels.optJSONObject(index)
                        ?: continue

                val title =
                    optStringOrNull(
                        item,
                        "title",
                        "name"
                    ) ?: continue

                results +=
                    newLiveSearchResponse(
                        title,
                        item.toString(),
                        TvType.Live
                    ) {
                        this.posterUrl = optImageUrl(item)
                        this.score = parseScore(item)
                    }
            }
        }

        optArray(
            json,
            "posters",
            "results",
            "items",
            "data"
        )?.let { posters ->

            for (index in 0 until posters.length()) {

                val item =
                    posters.optJSONObject(index)
                        ?: continue

                val title =
                    optStringOrNull(
                        item,
                        "title",
                        "name"
                    ) ?: continue

                val image = optImageUrl(item)
                val score = parseScore(item)

                when (detectItemType(item)) {

                    "live" -> {
                        results +=
                            newLiveSearchResponse(
                                title,
                                item.toString(),
                                TvType.Live
                            ) {
                                this.posterUrl = image
                                this.score = score
                            }
                    }

                    "serie" -> {
                        results +=
                            newTvSeriesSearchResponse(
                                title,
                                item.toString(),
                                TvType.TvSeries
                            ) {
                                this.posterUrl = image
                                this.score = score
                            }
                    }

                    else -> {
                        results +=
                            newMovieSearchResponse(
                                title,
                                item.toString(),
                                TvType.Movie
                            ) {
                                this.posterUrl = image
                                this.score = score
                            }
                    }
                }
            }
        }

        return results.filter { matchesSearchQuery(query, it.name) }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> =
        search(query)

    // =========================================================
    // LOAD
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val apiBase = getApiBaseUrl()

        val veri =
            parseObject(url)
                ?: return null

        val id =
            optIntOrNull(
                veri,
                "id",
                "_rectv_id",
                "movie_id",
                "serie_id",
                "channel_id"
            ) ?: return null

        val title =
            optStringOrNull(
                veri,
                "title",
                "name",
                "movie_name",
                "serie_name",
                "channel_name"
            ) ?: "RecTV"

        val image = optImageUrl(veri)

        val description =
            optStringOrNull(
                veri,
                "description",
                "plot",
                "overview"
            )

        val year =
            optIntOrNull(
                veri,
                "year",
                "release_year",
                "releaseYear"
            )

        val rawType =
            optStringOrNull(
                veri,
                "type",
                "content_type",
                "contentType",
                "kind",
                "model_type",
                "modelType",
                "_rectv_type"
            )
                ?.lowercase()
                .orEmpty()

        val section =
            optStringOrNull(
                veri,
                "_rectv_section"
            ).orEmpty()

        val detectedType = detectItemType(veri)

        val isSeries =
            rawType == "serie" ||
                rawType == "series" ||
                rawType == "tv" ||
                rawType == "tvshow" ||
                rawType == "dizi" ||
                detectedType == "serie" ||
                section.contains(
                    "dizi",
                    ignoreCase = true
                )

        val genres =
            parseGenres(
                optArray(
                    veri,
                    "genres"
                )
            )

        // =====================================================
        // DIZI
        // =====================================================

        if (isSeries) {

            val path =
                "/api/season/by/serie/$id/$SW_KEY/"

            val response =
                try {
                    app.get(
                        "$apiBase$path",
                        headers = getSignedHeaders(
                            method = "GET",
                            path = path
                        )
                    )
                } catch (e: Exception) {
                    Log.e(
                        "RecTV",
                        "Series request failed: ${e.message}",
                        e
                    )
                    return null
                }

            val seasons =
                parseArray(response.text) ?: run {

                    val objectResponse =
                        parseObject(response.text)

                    optArray(
                        objectResponse ?: JSONObject(),
                        "data",
                        "results",
                        "seasons",
                        "items"
                    )
                } ?: return null

            val episodes =
                mutableMapOf<
                    DubStatus,
                    MutableList<Episode>
                >()

            val numberRegex = Regex("\\d+")

            for (seasonIndex in 0 until seasons.length()) {

                val season =
                    seasons.optJSONObject(seasonIndex)
                        ?: continue

                val seasonTitle =
                    optStringOrNull(
                        season,
                        "title",
                        "name"
                    )
                        ?: "Sezon ${seasonIndex + 1}"

                val dubStatus =
                    when {
                        seasonTitle.contains(
                            "altyazı",
                            ignoreCase = true
                        ) || seasonTitle.contains(
                            "altyazi",
                            ignoreCase = true
                        ) -> DubStatus.Subbed

                        seasonTitle.contains(
                            "dublaj",
                            ignoreCase = true
                        ) -> DubStatus.Dubbed

                        else -> DubStatus.None
                    }

                val seasonNumber =
                    numberRegex
                        .find(seasonTitle)
                        ?.value
                        ?.toIntOrNull()

                val seasonEpisodes =
                    optArray(
                        season,
                        "episodes",
                        "bolumler",
                        "items"
                    ) ?: continue

                for (episodeIndex in 0 until seasonEpisodes.length()) {

                    val episodeJson =
                        seasonEpisodes.optJSONObject(episodeIndex)
                            ?: continue

                    val episodeTitle =
                        optStringOrNull(
                            episodeJson,
                            "title",
                            "name"
                        )
                            ?: "${episodeIndex + 1}. Bölüm"

                    val episodeNumber =
                        numberRegex
                            .find(episodeTitle)
                            ?.value
                            ?.toIntOrNull()

                    val episodeDescription =
                        if (
                            seasonTitle.contains(
                                ".S ",
                                ignoreCase = false
                            )
                        ) {
                            seasonTitle.substringAfter(".S ")
                        } else {
                            seasonTitle
                        }

                    episodes
                        .getOrPut(dubStatus) {
                            mutableListOf()
                        }
                        .add(
                            newEpisode(
                                episodeJson.toString()
                            ) {
                                this.name = episodeTitle
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.description = episodeDescription
                                this.posterUrl = image
                            }
                        )
                }
            }

            if (episodes.isEmpty()) {
                return null
            }

            val series =
                newAnimeLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    comingSoonIfNone = false
                ) {
                    this.episodes =
                        episodes
                            .mapValues { it.value.toList() }
                            .toMutableMap()

                    this.posterUrl = image
                    this.plot = description
                    this.year = year
                    this.tags = genres
                }

            applyMetadata(series, veri)

            return series
        }

        // =====================================================
        // CANLI TV
        // =====================================================

        val isLive =
            optStringOrNull(
                veri,
                "label",
                "status"
            ).equals(
                "CANLI",
                ignoreCase = true
            ) || detectedType == "live" ||
                rawType.contains("channel") ||
                rawType.contains("live") ||
                rawType.contains("sport") ||
                section.equals(
                    "Spor",
                    ignoreCase = true
                ) ||
                section.equals(
                    "Canlı TV",
                    ignoreCase = true
                ) ||
                veri.has("channel_id")

        if (isLive) {

            val fullChannel =
                if (!hasArrayItems(veri, "sources")) {

                    val path =
                        "/api/channel/by/$id/$SW_KEY/"

                    try {
                        app.get(
                            "$apiBase$path",
                            headers = getSignedHeaders(
                                method = "GET",
                                path = path
                            )
                        ).let {
                            parseObject(it.text)
                        } ?: veri
                    } catch (e: Exception) {
                        Log.e(
                            "RecTV",
                            "Channel request failed: ${e.message}",
                            e
                        )
                        veri
                    }

                } else {
                    veri
                }

            val categories =
                parseCategories(
                    optArray(
                        fullChannel,
                        "categories"
                    )
                )

            return newLiveStreamLoadResponse(
                optStringOrNull(
                    fullChannel,
                    "title",
                    "name",
                    "channel_name"
                ) ?: title,
                url,
                fullChannel.toString()
            ) {
                this.posterUrl =
                    optImageUrl(fullChannel) ?: image

                this.plot =
                    optStringOrNull(
                        fullChannel,
                        "description",
                        "plot",
                        "overview"
                    ) ?: description

                this.tags = categories
            }
        }

        // =====================================================
        // FILM
        // =====================================================

        val fullMovie =
            if (!hasArrayItems(veri, "sources")) {

                val path =
                    "/api/movie/by/$id/$SW_KEY/"

                try {
                    app.get(
                        "$apiBase$path",
                        headers = getSignedHeaders(
                            method = "GET",
                            path = path
                        )
                    ).let {
                        parseObject(it.text)
                    } ?: veri
                } catch (e: Exception) {
                    Log.e(
                        "RecTV",
                        "Movie request failed: ${e.message}",
                        e
                    )
                    veri
                }

            } else {
                veri
            }

        val movie =
            newMovieLoadResponse(
                optStringOrNull(
                    fullMovie,
                    "title",
                    "name",
                    "movie_name"
                ) ?: title,
                url,
                TvType.Movie,
                fullMovie.toString()
            ) {
                this.posterUrl =
                    optImageUrl(fullMovie) ?: image

                this.plot =
                    optStringOrNull(
                        fullMovie,
                        "description",
                        "plot",
                        "overview"
                    ) ?: description

                this.year =
                    optIntOrNull(
                        fullMovie,
                        "year",
                        "release_year",
                        "releaseYear"
                    ) ?: year

                this.tags =
                    parseGenres(
                        optArray(
                            fullMovie,
                            "genres"
                        )
                    )
            }

        applyMetadata(movie, fullMovie)

        return movie
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
    ): Boolean {

        if (
            data.startsWith("http://") ||
            data.startsWith("https://")
        ) {

            val linkType =
                when {
                    data.contains(
                        ".m3u8",
                        ignoreCase = true
                    ) -> ExtractorLinkType.M3U8

                    data.contains(
                        ".mp4",
                        ignoreCase = true
                    ) -> ExtractorLinkType.VIDEO

                    else -> INFER_TYPE
                }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    type = linkType
                ) {
                    this.referer = REFERER
                    this.headers = mapOf(
                        "Referer" to REFERER,
                        "User-Agent" to USER_AGENT
                    )
                    this.quality =
                        Qualities.Unknown.value
                }
            )

            return true
        }

        val sources = mutableListOf<JSONObject>()
        val item = parseObject(data)

        item
            ?.optJSONArray("sources")
            ?.let { array ->
                for (index in 0 until array.length()) {
                    array
                        .optJSONObject(index)
                        ?.let {
                            sources += it
                        }
                }
            }

        if (
            sources.isEmpty() &&
            item != null
        ) {

            val apiBase = getApiBaseUrl()

            val id =
                optIntOrNull(
                    item,
                    "id",
                    "_rectv_id",
                    "movie_id",
                    "serie_id",
                    "channel_id"
                ) ?: 0

            if (id > 0) {

                try {

                    val type =
                        optStringOrNull(
                            item,
                            "_rectv_type"
                        )
                            ?.lowercase()
                            ?: detectItemType(item)

                    if (type == "movie") {

                        val path =
                            "/api/movie/by/$id/$SW_KEY/"

                        val response =
                            app.get(
                                "$apiBase$path",
                                headers = getSignedHeaders(
                                    method = "GET",
                                    path = path
                                )
                            )

                        parseObject(response.text)
                            ?.optJSONArray("sources")
                            ?.let { array ->
                                for (index in 0 until array.length()) {
                                    array
                                        .optJSONObject(index)
                                        ?.let {
                                            sources += it
                                        }
                                }
                            }

                    } else {

                        val section =
                            optStringOrNull(
                                item,
                                "_rectv_section"
                            ).orEmpty()

                        val isChannel =
                            type == "live" ||
                                section.equals(
                                    "Canlı TV",
                                    ignoreCase = true
                                ) ||
                                section.equals(
                                    "Spor",
                                    ignoreCase = true
                                ) ||
                                item.has("channel_id") ||
                                optStringOrNull(
                                    item,
                                    "label"
                                ).equals(
                                    "CANLI",
                                    ignoreCase = true
                                )

                        if (isChannel) {

                            val path =
                                "/api/channel/by/$id/$SW_KEY/"

                            val response =
                                app.get(
                                    "$apiBase$path",
                                    headers = getSignedHeaders(
                                        method = "GET",
                                        path = path
                                    )
                                )

                            parseObject(response.text)
                                ?.optJSONArray("sources")
                                ?.let { array ->
                                    for (index in 0 until array.length()) {
                                        array
                                            .optJSONObject(index)
                                            ?.let {
                                                sources += it
                                            }
                                    }
                                }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(
                        "RecTV",
                        "Failed to fetch item sources: ${e.message}",
                        e
                    )
                }
            }
        }

        if (sources.isEmpty()) {

            item
                ?.optJSONArray("source")
                ?.let { array ->
                    for (index in 0 until array.length()) {
                        array
                            .optJSONObject(index)
                            ?.let {
                                sources += it
                            }
                    }
                }
        }

        if (sources.isEmpty()) {
            item
                ?.optJSONObject("source")
                ?.let {
                    sources += it
                }
        }

        if (sources.isEmpty()) {
            return false
        }

        var delivered = false

        for (source in sources) {

            val encrypted =
                optStringOrNull(
                    source,
                    "enc_url",
                    "encUrl"
                )

            val sourceId =
                optIntOrNull(
                    source,
                    "id",
                    "source_id",
                    "sourceId"
                )

            val locked =
                optBooleanOrNull(
                    source,
                    "locked",
                    "is_locked",
                    "isLocked"
                ) == true

            val enc =
                if (!encrypted.isNullOrBlank()) {
                    encrypted
                } else if (
                    (locked || encrypted.isNullOrBlank()) &&
                    sourceId != null
                ) {
                    unlockSource(sourceId)
                } else {
                    null
                }

            val streamUrl =
                if (!enc.isNullOrBlank()) {
                    decryptEncUrl(enc)
                } else {
                    optStringOrNull(
                        source,
                        "url",
                        "stream_url",
                        "streamUrl",
                        "file"
                    )
                }

            if (streamUrl.isNullOrBlank()) {
                continue
            }

            val sourceTitle =
                optStringOrNull(
                    source,
                    "title",
                    "quality",
                    "type",
                    "name"
                ) ?: "Kaynak"

            val sourceType =
                optStringOrNull(
                    source,
                    "type"
                )
                    ?.lowercase()
                    .orEmpty()

            val linkType =
                when {
                    streamUrl.contains(
                        ".m3u8",
                        ignoreCase = true
                    ) ||
                        streamUrl.contains(
                            "master.m3u8",
                            ignoreCase = true
                        ) ||
                        sourceType == "m3u8" ->
                        ExtractorLinkType.M3U8

                    sourceType == "mp4" ||
                        streamUrl.endsWith(
                            ".mp4",
                            ignoreCase = true
                        ) ->
                        ExtractorLinkType.VIDEO

                    else -> INFER_TYPE
                }

            val quality =
                optStringOrNull(
                    source,
                    "quality",
                    "resolution"
                )
                    ?.filter { it.isDigit() }
                    ?.toIntOrNull()
                    ?: Qualities.Unknown.value

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - $sourceTitle",
                    url = streamUrl,
                    type = linkType
                ) {
                    this.referer = REFERER
                    this.headers = mapOf(
                        "Referer" to REFERER,
                        "User-Agent" to USER_AGENT
                    )
                    this.quality = quality
                }
            )

            delivered = true
        }

        return delivered
    }

    // =========================================================
    // VIDEO INTERCEPTOR
    // =========================================================

    override fun getVideoInterceptor(
        extractorLink: ExtractorLink
    ): Interceptor {

        return Interceptor { chain ->

            val modifiedRequest =
                chain
                    .request()
                    .newBuilder()
                    .removeHeader("If-None-Match")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .build()

            chain.proceed(modifiedRequest)
        }
    }

    // =========================================================
    // GENRES
    // =========================================================

    private fun parseGenres(
        array: JSONArray?
    ): List<String>? {

        if (
            array == null ||
            array.length() == 0
        ) {
            return null
        }

        val result = mutableListOf<String>()

        for (index in 0 until array.length()) {

            when (val value = array.opt(index)) {

                is JSONObject -> {
                    optStringOrNull(
                        value,
                        "title",
                        "name",
                        "label"
                    )?.let {
                        result += it
                    }
                }

                is String -> {
                    value
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            result += it
                        }
                }
            }
        }

        return result
            .distinct()
            .takeIf {
                it.isNotEmpty()
            }
    }

    // =========================================================
    // CATEGORIES
    // =========================================================

    private fun parseCategories(
        array: JSONArray?
    ): List<String>? {

        if (
            array == null ||
            array.length() == 0
        ) {
            return null
        }

        val result = mutableListOf<String>()

        for (index in 0 until array.length()) {

            when (val value = array.opt(index)) {

                is JSONObject -> {
                    optStringOrNull(
                        value,
                        "title",
                        "name",
                        "label"
                    )?.let {
                        result += it
                    }
                }

                is String -> {
                    value
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            result += it
                        }
                }
            }
        }

        return result
            .distinct()
            .takeIf {
                it.isNotEmpty()
            }
    }
}
