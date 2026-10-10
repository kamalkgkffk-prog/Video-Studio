package com.videoindirici.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// ---- Masaüstü uygulamadaki kalite_kartlari / video_secici / playlist mantığının Kotlin karşılığı

private val STD = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)
private val LISTE_RE = Regex("[?&]list=|/playlist|/sets/|/album/|/showcase/", RegexOption.IGNORE_CASE)
private val YT_VIDEO_RE = Regex("[?&]v=|youtu\\.be/|/shorts/|/live/", RegexOption.IGNORE_CASE)
private val MEDYA_UZANTI = setOf("mp4", "mkv", "webm", "mov", "m4v", "mp3", "m4a", "opus", "ogg", "wav", "aac")

data class Kalite(
    val ad: String,
    val h: Int,
    val fid: String?,
    val sesli: Boolean,
    val boyut: Double,
)

data class SesKalite(val ad: String, val kbps: Int)

val SES_KALITELER = listOf(
    SesKalite("Yüksek Kalite", 320),
    SesKalite("Orta Kalite", 192),
    SesKalite("Standart", 128),
)

data class ListeVideo(val no: Int, val baslik: String, val sureSn: Int)

/** sadece = link yalnızca liste linki (belirli bir videoya işaret etmiyor) */
data class Liste(val baslik: String, val adet: Int, val sadece: Boolean, val videolar: List<ListeVideo>)

data class Bilgi(
    val url: String,
    val baslik: String,
    val kanal: String,
    val sureSn: Int,
    val resim: String?,
    val kaliteler: List<Kalite>,
    val liste: Liste? = null,
)

fun kaliteAdi(h: Int) = when {
    h >= 4320 -> "8K Ultra HD"
    h >= 2160 -> "4K Ultra HD"
    h >= 1440 -> "2K QHD"
    h >= 1080 -> "1080p Full HD"
    h >= 720 -> "720p HD"
    h >= 480 -> "480p SD"
    else -> "${h}p"
}

fun boyutYaz(b: Double): String {
    if (b <= 0) return "—"
    val mb = b / (1024 * 1024)
    return if (mb >= 1024) "~%.1f GB".format(mb / 1024) else "~%.1f MB".format(mb)
}

fun sureYaz(sn: Int): String {
    if (sn <= 0) return ""
    val sa = sn / 3600
    val dk = (sn % 3600) / 60
    val s = sn % 60
    return if (sa > 0) "%d:%02d:%02d".format(sa, dk, s) else "%d:%02d".format(dk, s)
}

/** [1,2,3,5,7,8] -> "1-3,5,7-8" (yt-dlp --playlist-items biçimi) */
fun aralikYaz(nolar: Collection<Int>): String {
    val n = nolar.sorted()
    val parca = ArrayList<String>()
    var i = 0
    while (i < n.size) {
        var j = i
        while (j + 1 < n.size && n[j + 1] == n[j] + 1) j++
        parca += if (i == j) "${n[i]}" else "${n[i]}-${n[j]}"
        i = j + 1
    }
    return parca.joinToString(",")
}

/** Masaüstündeki hata_ipucu karşılığı: site yt-dlp'yi bozmuş olabilir. */
fun hataIpucu(hata: String?): String {
    val h = (hata ?: "").lowercase()
    return when {
        listOf("sign in", "403", "http error 4", "unable to extract", "nsig", "player").any { it in h } ->
            "\n\nİpucu: yt-dlp eskimiş olabilir. Alttaki “yt-dlp'yi güncelle” düğmesine basıp tekrar dene."
        "private" in h || "unavailable" in h -> "\n\nVideo özel veya erişilemez olabilir."
        else -> ""
    }
}

object Indirici {

    /** Linki çözümler: oynatma listesi varsa onu, ardından tek video için kalite kartlarını çıkarır. */
    suspend fun analiz(url: String): Bilgi = withContext(Dispatchers.IO) {
        var liste: Liste? = null
        var videoLink = url

        if (LISTE_RE.containsMatchIn(url)) {
            val pl = try {
                val req = YoutubeDLRequest(url).apply {
                    addOption("-J")
                    addOption("--flat-playlist")
                    addOption("--no-warnings")
                }
                JSONObject(YoutubeDL.getInstance().execute(req).out)
            } catch (_: Exception) { null }

            if (pl != null && pl.optString("_type") == "playlist") {
                val ham = pl.optJSONArray("entries")
                val vids = ArrayList<ListeVideo>()
                var ilkUrl: String? = null
                if (ham != null) {
                    for (i in 0 until ham.length()) {
                        val e = ham.optJSONObject(i) ?: continue
                        if (ilkUrl == null) {
                            ilkUrl = e.optString("url").ifEmpty { e.optString("webpage_url") }.ifEmpty {
                                e.optString("id").takeIf { it.isNotEmpty() }?.let { "https://www.youtube.com/watch?v=$it" } ?: ""
                            }.ifEmpty { null }
                        }
                        vids += ListeVideo(
                            no = i + 1,
                            baslik = e.optString("title").ifEmpty { "Başlıksız video" },
                            sureSn = e.optDouble("duration", 0.0).toInt(),
                        )
                    }
                }
                val sadece = !YT_VIDEO_RE.containsMatchIn(url)
                if (sadece && vids.isEmpty()) error("Oynatma listesi boş veya erişilemiyor.")
                if (sadece && ilkUrl != null) videoLink = ilkUrl
                val adet = pl.optInt("playlist_count", vids.size).takeIf { it > 0 } ?: vids.size
                if (adet > 1 || sadece) {
                    liste = Liste(pl.optString("title").ifEmpty { "Oynatma listesi" }, adet, sadece, vids)
                }
            }
        }

        val tek = tekVideo(videoLink)
        tek.copy(url = url, liste = liste)
    }

    private fun karsilastir(a: Triple<Int, Int, Double>, b: Triple<Int, Int, Double>): Int {
        if (a.first != b.first) return a.first - b.first
        if (a.second != b.second) return a.second - b.second
        return a.third.compareTo(b.third)
    }

    private fun tekVideo(url: String): Bilgi {
        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
        }
        val info = YoutubeDL.getInstance().getInfo(req)
        val sure = info.duration
        val fm = info.formats.orEmpty()

        fun boyut(f: VideoFormat): Double {
            var b = maxOf(f.fileSize, f.fileSizeApproximate).toDouble()
            if (b <= 0 && f.tbr > 0 && sure > 0) b = f.tbr * 1000.0 / 8 * sure
            return b
        }

        val sesler = fm.filter { it.vcodec == "none" && !it.acodec.isNullOrEmpty() && it.acodec != "none" }
        val enSes = sesler.maxByOrNull { boyut(it) }
        val sesB = enSes?.let { boyut(it) } ?: 0.0

        // Yüksekliğe göre grupla; her grup için en uygun (avc/mp4, yüksek bitrate) formatı seç
        val gruplar = HashMap<Int, Pair<Triple<Int, Int, Double>, VideoFormat>>()
        for (f in fm) {
            if (f.vcodec.isNullOrEmpty() || f.vcodec == "none") continue
            val w = f.width
            val h = f.height
            if (w <= 0 && h <= 0) continue
            val kisa = if (w > 0 && h > 0) minOf(w, h) else maxOf(w, h)
            val std = STD.filter { it <= kisa * 1.06 }.maxOrNull() ?: continue
            val avc = f.vcodec.startsWith("avc") && f.ext == "mp4"
            val puan = Triple(if (avc) 1 else 0, f.tbr, boyut(f))
            val eski = gruplar[std]
            if (eski == null || karsilastir(puan, eski.first) > 0) gruplar[std] = puan to f
        }

        val kartlar = gruplar.keys.sortedDescending().map { std ->
            val f = gruplar[std]!!.second
            val sesli = !f.acodec.isNullOrEmpty() && f.acodec != "none"
            Kalite(kaliteAdi(std), std, f.formatId, sesli, boyut(f) + if (sesli) 0.0 else sesB)
        }.ifEmpty { listOf(Kalite("En iyi kalite", 0, null, false, 0.0)) }

        return Bilgi(
            url = url,
            baslik = info.title ?: "Başlıksız video",
            kanal = info.uploader ?: "",
            sureSn = sure,
            resim = info.thumbnail,
            kaliteler = kartlar,
        )
    }

    /**
     * İndirir, sonra Downloads/VideoIndirici klasörüne kopyalar.
     * @param tum   true: oynatma listesini indir (secim null ise hepsi)
     * @param secim listeden seçilen video numaraları (1 tabanlı); null = hepsi
     * @param ilerleme (yüzde 0..100 ya da -1 = yüzde yok, durum yazısı)
     * @return kullanıcıya gösterilecek özet
     */
    suspend fun indir(
        ctx: Context,
        bilgi: Bilgi,
        video: Boolean,
        kalite: Kalite?,
        ses: SesKalite?,
        tum: Boolean,
        secim: Set<Int>?,
        islemId: String,
        ilerleme: (Float, String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val ls = bilgi.liste
        val toplu = tum && ls != null
        val tmp = File(ctx.cacheDir, "indirme_$islemId").apply { deleteRecursively(); mkdirs() }

        val req = YoutubeDLRequest(bilgi.url).apply {
            addOption("--windows-filenames")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            if (ls != null && (toplu || ls.sadece)) {
                addOption("--yes-playlist")
                addOption("--ignore-errors")
                if (!toplu) addOption("--playlist-items", "1")
                else if (secim != null) addOption("--playlist-items", aralikYaz(secim))
            } else {
                addOption("--no-playlist")
            }
            val sablon = if (toplu) "%(playlist_title).100s/%(playlist_index)03d - %(title).150s.%(ext)s"
            else "%(title).150s.%(ext)s"
            addOption("-o", File(tmp, sablon).absolutePath)

            if (!video) {
                addOption("-f", "bestaudio/best")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "${ses?.kbps ?: 320}K")
            } else {
                val fid = kalite?.fid
                if (toplu || fid == null) {
                    // Liste: her videoda aynı yükseklik hedeflenir (format kimliği videodan videoya değişir)
                    addOption("-f", "bv*+ba/b")
                    val h = kalite?.h ?: 0
                    if (h > 0) addOption("-S", "res:$h,vcodec:h264,acodec:aac")
                } else {
                    addOption("-f", if (kalite.sesli) fid else "$fid+ba[ext=m4a]/$fid+ba/$fid")
                }
                addOption("--merge-output-format", "mp4")
            }
        }

        // ---- genel ilerleme: (video sırası, aşama) -> tek yüzde
        val streams = if (!video) 1 else if (!toplu && kalite?.sesli == true) 1 else 2
        var idx = 1
        var total = if (toplu) (secim?.size ?: ls!!.adet) else 1
        var stage = 0
        val itemRe = Regex("Downloading item (\\d+) of (\\d+)")

        YoutubeDL.getInstance().execute(req, islemId) { yuzde, kalanSn, satir ->
            itemRe.find(satir)?.let {
                idx = it.groupValues[1].toInt(); total = it.groupValues[2].toInt(); stage = 0
            }
            if (satir.startsWith("[download] Destination:")) stage = minOf(stage + 1, streams)
            val eta = if (kalanSn > 0) "Kalan ${sureYaz(kalanSn.toInt())}" else ""
            val sira = if (total > 1) "Video $idx/$total  ·  " else ""
            when {
                "[Merger]" in satir -> ilerleme(-1f, "Görüntü ve ses birleştiriliyor…")
                "[ExtractAudio]" in satir -> ilerleme(-1f, "MP3'e dönüştürülüyor…")
                yuzde >= 0f -> {
                    if (stage == 0) stage = 1
                    val st = stage.coerceIn(1, streams)
                    val frac = if (streams == 2) {
                        val (lo, hi) = if (st == 1) 0.0 to 0.92 else 0.92 to 1.0
                        lo + (hi - lo) * yuzde / 100.0
                    } else yuzde / 100.0
                    val genel = ((idx - 1) + frac) / total * 100.0
                    ilerleme(genel.toFloat().coerceIn(0f, 100f), sira + eta)
                }
            }
        }

        val dosyalar = tmp.walkTopDown().filter {
            it.isFile && it.extension.lowercase() in MEDYA_UZANTI
        }.toList()
        if (dosyalar.isEmpty()) error("İndirilen dosya bulunamadı.")
        for (d in dosyalar) kaydet(ctx, d, d.parentFile!!.relativeTo(tmp).path, video)
        tmp.deleteRecursively()
        if (dosyalar.size == 1) dosyalar[0].name else "${dosyalar.size} dosya"
    }

    fun iptal(islemId: String) {
        try { YoutubeDL.getInstance().destroyProcessById(islemId) } catch (_: Exception) {}
    }

    /** Android 10+ : MediaStore ile izin istemeden Downloads/VideoIndirici[/alt klasör]'e yazar. */
    private fun kaydet(ctx: Context, src: File, altKlasor: String, video: Boolean) {
        val mime = if (video) "video/mp4" else "audio/mpeg"
        val alt = altKlasor.replace('\\', '/').trim('/').let { if (it.isEmpty() || it == ".") "" else "/$it" }
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoIndirici" + alt)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: error("Dosya oluşturulamadı.")
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VideoIndirici$alt")
            dir.mkdirs()
            src.copyTo(File(dir, src.name), overwrite = true)
        }
    }
}
