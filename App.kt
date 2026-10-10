package com.videoindirici.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {
    companion object {
        @Volatile var hazir = false
        @Volatile var hata: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        // yt-dlp (python) ve ffmpeg ilk açılışta uygulama klasörüne açılır
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            hazir = true
        } catch (e: Exception) {
            hata = e.message ?: "Başlatılamadı"
        }
    }
}
