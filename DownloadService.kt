package com.videoindirici.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** İndirme işinin tüm bilgisi; arayüz (ViewModel) ile servis arasında paylaşılır. */
class DlIs(
    val bilgi: Bilgi,
    val video: Boolean,
    val kalite: Kalite?,
    val ses: SesKalite?,
    val tum: Boolean,
    val secim: Set<Int>?,
)

/** Servis ile arayüz arasındaki ortak durum (Compose bunu doğrudan izler). */
object DlDurum {
    var aktif by mutableStateOf(false)
    var yuzde by mutableFloatStateOf(0f)
    var durum by mutableStateOf<String?>(null)
    var mesaj by mutableStateOf<String?>(null)
    @Volatile var is_: DlIs? = null
}

class DownloadService : Service() {

    companion object {
        const val KANAL = "indirme"
        const val BILDIRIM_ID = 1
        const val SONUC_ID = 2
        const val ACTION_BASLAT = "com.videoindirici.app.BASLAT"
        const val ACTION_IPTAL = "com.videoindirici.app.IPTAL"

        fun baslat(ctx: Context, is_: DlIs) {
            DlDurum.is_ = is_
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, DownloadService::class.java).setAction(ACTION_BASLAT))
        }

        fun iptal(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_IPTAL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var is_: Job? = null
    private var islemId = ""
    private var iptalEdildi = false
    private var wake: PowerManager.WakeLock? = null
    private var sonBildirim = 0L
    private var sonYuzde = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_IPTAL -> {
                iptalEdildi = true
                Indirici.iptal(islemId)
            }
            ACTION_BASLAT -> baslat()
            else -> if (is_ == null) stopSelf()        // sistem servisi yeniden başlattıysa (iş yok): kapan
        }
        return START_NOT_STICKY
    }

    private fun baslat() {
        val is_ = DlDurum.is_ ?: return stopSelf()
        if (this.is_?.isActive == true) return
        kanalKur()
        val ilk = bildirim("İndirme başlatılıyor…", -1f)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(BILDIRIM_ID, ilk, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(BILDIRIM_ID, ilk)

        // Ekran kapansa da CPU uyumasın (en fazla 6 saat)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoIndirici:indirme").apply { acquire(6 * 60 * 60 * 1000L) }

        islemId = "dl_" + System.currentTimeMillis()
        iptalEdildi = false
        sonYuzde = -1
        DlDurum.aktif = true
        DlDurum.yuzde = 0f
        DlDurum.durum = "İndirme başlatılıyor…"
        DlDurum.mesaj = null

        this.is_ = scope.launch {
            var sonuc: String
            var basarili = false
            try {
                val ad = Indirici.indir(
                    applicationContext, is_.bilgi, is_.video, is_.kalite, is_.ses, is_.tum, is_.secim, islemId,
                ) { p, a -> scope.launch { ilerle(p, a) } }
                DlDurum.yuzde = 100f
                DlDurum.durum = "Tamamlandı"
                sonuc = "Kaydedildi: İndirilenler/VideoIndirici — $ad"
                basarili = true
            } catch (e: Exception) {
                DlDurum.durum = if (iptalEdildi) "İptal edildi" else "Hata"
                sonuc = if (iptalEdildi) "İndirme iptal edildi."
                else (e.message ?: "İndirme başarısız.").lines().lastOrNull { it.isNotBlank() }.orEmpty().take(300) +
                    hataIpucu(e.message)
                DlDurum.yuzde = 0f
                java.io.File(cacheDir, "indirme_$islemId").deleteRecursively()   // yarım kalan dosyalar
            }
            DlDurum.mesaj = sonuc
            DlDurum.aktif = false
            bitir(sonuc, basarili)
        }
    }

    private fun ilerle(p: Float, a: String) {
        if (p >= 0f) {
            DlDurum.yuzde = p
            DlDurum.durum = "İndiriliyor…  %${p.toInt()}  $a"
        } else DlDurum.durum = a
        // Bildirimi saniyede en fazla bir kez güncelle
        val simdi = System.currentTimeMillis()
        val y = p.toInt()
        if (simdi - sonBildirim < 1000 && (p < 0f || y == sonYuzde)) return
        sonBildirim = simdi
        if (p >= 0f) sonYuzde = y
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(BILDIRIM_ID, bildirim(a.ifEmpty { "İndiriliyor…" }, p))
    }

    private fun bitir(sonuc: String, basarili: Boolean) {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!iptalEdildi) {
            val acPi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, KANAL)
                .setSmallIcon(if (basarili) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (basarili) "İndirme tamamlandı" else "İndirme başarısız")
                .setContentText(sonuc)
                .setStyle(NotificationCompat.BigTextStyle().bigText(sonuc))
                .setContentIntent(acPi)
                .setAutoCancel(true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(SONUC_ID, n)
        }
        stopSelf()
    }

    private fun bildirim(metin: String, yuzde: Float): Notification {
        val acPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val iptalPi = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_IPTAL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val baslik = DlDurum.is_?.bilgi?.baslik ?: "İndiriliyor"
        return NotificationCompat.Builder(this, KANAL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(baslik)
            .setContentText(if (yuzde >= 0f) "%${yuzde.toInt()}  ·  $metin" else metin)
            .setProgress(100, yuzde.coerceAtLeast(0f).toInt(), yuzde < 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(acPi)
            .addAction(0, "İptal", iptalPi)
            .build()
    }

    private fun kanalKur() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(KANAL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(KANAL, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    override fun onDestroy() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
