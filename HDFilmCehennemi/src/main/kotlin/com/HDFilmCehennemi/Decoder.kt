package com.HDFilmCehennemi

import android.util.Base64
import java.text.Normalizer

data class SubItem(val url: String, val label: String, val lang: String)
data class Cand(val urls: List<String>, val strict: Set<String>)

object Decoder {
    private val IC = RegexOption.IGNORE_CASE

    // ---------- Metin yardımcıları ----------
    private fun fold(s: String): String {
        val t = s.lowercase()
            .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
            .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        return Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    fun norm(s: String): String = fold(s).replace(Regex("[^a-z0-9]+"), "")

    fun slugify(s: String): String =
        fold(s).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun unesc(s: String): String {
        var t = s.replace("\\/", "/")
        t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        t = Regex("""\\x([0-9a-fA-F]{2})""").replace(t) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return t.replace("&amp;", "&")
    }

    // ---------- p.a.c.k.e.r çözücü ----------
    private val packedRe = Regex(
        """\}\('((?:[^'\\]|\\.)*)',\s*(\d+),\s*(\d+),\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun unpack(src: String): List<String> {
        val out = ArrayList<String>()
        for (m in packedRe.findAll(src)) {
            try {
                val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
                val radix = m.groupValues[2].toInt()
                val count = m.groupValues[3].toInt()
                val dict = m.groupValues[4].split("|")

                fun enc(c: Int): String {
                    val pre = if (c < radix) "" else enc(c / radix)
                    val r = c % radix
                    return pre + (if (r > 35) (r + 29).toChar().toString() else r.toString(36))
                }

                val map = HashMap<String, String>()
                for (i in count - 1 downTo 0) {
                    val k = enc(i)
                    map[k] = dict.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: k
                }
                out.add(Regex("""\b\w+\b""").replace(payload) { map[it.value] ?: it.value })
            } catch (e: Exception) {
            }
        }
        return out
    }

    // ---------- Base64 ----------
    private val atobRe = Regex("""atob\(\s*["']([A-Za-z0-9+/=_-]{16,})["']\s*\)""")
    private val longB64 = Regex("""["']([A-Za-z0-9+/]{80,}={0,2})["']""")

    private fun b64(src: String): List<String> {
        val out = ArrayList<String>()
        val raws = atobRe.findAll(src).map { it.groupValues[1] }.take(20).toList() +
            longB64.findAll(src).map { it.groupValues[1] }.take(20).toList()
        for (raw in raws) {
            val bytes = try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (e: Exception) {
                try {
                    Base64.decode(raw, Base64.URL_SAFE)
                } catch (e2: Exception) {
                    null
                }
            } ?: continue
            val d = String(bytes, Charsets.UTF_8)
            if (d.contains("http", true) || d.contains("eval(") || d.contains("m3u8", true)) out.add(d)
        }
        return out
    }

    // ---------- Tüm çözülmüş metinleri topla ----------
    fun collectBlobs(html: String): List<String> {
        val out = LinkedHashSet<String>()
        var queue = ArrayList<String>()
        queue.add(html)
        var round = 0
        while (queue.isNotEmpty() && round < 3) {
            val next = ArrayList<String>()
            for (b in queue) {
                val u = unesc(b)
                if (!out.add(u)) continue
                next.addAll(unpack(u))
                next.addAll(b64(u))
            }
            queue = next
            round++
        }
        return out.toList()
    }

    // ---------- Akış adayları ----------
    private val urlRe = Regex("""https?://[^\s"'<>\\)]+""")
    private val streamRe = Regex("""\.m3u8|\.mp4|master\.txt|/hls\d?/""", IC)
    private val badRe = Regex("""\.(?:vtt|srt|jpg|jpeg|png|gif|webp|css|js|svg|ico|woff2?)(?:\?|#|\z)""", IC)
    private val fileRe = Regex("""(?:file|src|source|video_location)["']?\s*[:=]\s*["'](https?://[^"']+)["']""", IC)

    fun candFrom(blobs: List<String>): Cand {
        val urls = LinkedHashSet<String>()
        val strict = LinkedHashSet<String>()
        for (b in blobs) {
            for (m in fileRe.findAll(b)) {
                val u = m.groupValues[1]
                if (streamRe.containsMatchIn(u) && !badRe.containsMatchIn(u)) {
                    urls.add(u)
                    strict.add(u)
                }
            }
            for (m in urlRe.findAll(b)) {
                val u = m.value.trimEnd(',', ';', '.')
                if (streamRe.containsMatchIn(u) && !badRe.containsMatchIn(u)) urls.add(u)
            }
        }
        return Cand(urls.toList(), strict)
    }

    // ---------- Altyazılar ----------
    private val jsonTrackRe = Regex(
        """["']file["']\s*:\s*["']([^"']+?\.(?:vtt|srt)[^"']*)["'][^{}]*?["']label["']\s*:\s*["']([^"']*)["']""",
        IC
    )
    private val tagRe = Regex("""<track[^>]+>""", IC)
    private val srcAttr = Regex("""src\s*=\s*["']([^"']+)["']""", IC)
    private val labelAttr = Regex("""label\s*=\s*["']([^"']*)["']""", IC)
    private val langAttr = Regex("""srclang\s*=\s*["']([^"']*)["']""", IC)

    private fun absUrl(u: String, origin: String): String = when {
        u.startsWith("http", true) -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> origin.trimEnd('/') + u
        else -> origin.trimEnd('/') + "/" + u
    }

    private fun langOf(label: String, code: String, url: String): String {
        val c = code.lowercase()
        if (c.startsWith("tr")) return "tr"
        if (c.startsWith("en")) return "en"
        val f = fold(label + " " + url)
        return when {
            Regex("turk|turkce|\\btr\\b|/tr[._/-]").containsMatchIn(f) -> "tr"
            Regex("ingiliz|english|\\ben\\b|\\beng\\b|/en[._/-]").containsMatchIn(f) -> "en"
            else -> if (c.isNotBlank()) c.take(2) else "xx"
        }
    }

    fun extractSubs(blob: String, origin: String): List<SubItem> {
        val out = ArrayList<SubItem>()
        for (m in jsonTrackRe.findAll(blob)) {
            val url = absUrl(m.groupValues[1], origin)
            val label = m.groupValues[2]
            out.add(SubItem(url, label, langOf(label, "", url)))
        }
        for (m in tagRe.findAll(blob)) {
            val tag = m.value
            val src = srcAttr.find(tag)?.groupValues?.get(1) ?: continue
            if (!Regex("""\.(?:vtt|srt)""", IC).containsMatchIn(src)) continue
            val url = absUrl(src, origin)
            val label = labelAttr.find(tag)?.groupValues?.get(1).orEmpty()
            val code = langAttr.find(tag)?.groupValues?.get(1).orEmpty()
            out.add(SubItem(url, label, langOf(label, code, url)))
        }
        return out
    }
}
