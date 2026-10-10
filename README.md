# Video İndirici (Android)

Masaüstü uygulamadaki **Video İndir** sekmesinin Android karşılığı.
Kotlin + Jetpack Compose + [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (yt-dlp ve ffmpeg APK'nın içinde gelir).

## APK nasıl alınır

1. [Android Studio](https://developer.android.com/studio) kur (Koala veya daha yeni).
2. `File ▸ Open` ile bu klasörü (`VideoIndirici`) aç. Gradle senkronizasyonu bitsin (ilk seferde birkaç dakika).
3. Telefonda **Geliştirici seçenekleri ▸ USB hata ayıklama** aç, kabloyla bağla, ▶ Run ile direkt telefona kur.
4. Dağıtılabilir APK için: `Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)`
   → `app/build/outputs/apk/debug/app-debug.apk`
   Kendi sitenden dağıtacaksan `Build ▸ Generate Signed App Bundle / APK` ile imzalı release APK üret.

Komut satırından: `./gradlew assembleDebug` (Gradle wrapper'ı Android Studio ilk açılışta oluşturur).

## Özellikler

- Link yapıştır / başka uygulamadan **Paylaş ▸ Video İndirici** ile link al
- Analiz: başlık, küçük resim, süre, kalite kartları (144p–8K, tahmini boyut)
- Oynatma listesi: liste linki otomatik algılanır, "tüm listeyi indir" anahtarı, "Videoları seç…" ile tek tek seçme,
  toplu indirmede genel ilerleme (Video 3/12), dosyalar liste adıyla bir alt klasöre `001 - …` sırasıyla kaydedilir
- Arka planda indirme: ön plan servisi + bildirim çubuğunda ilerleme ve "İptal" düğmesi; ekran kapansa
  ya da uygulama kapatılsa da sürer (Android 13+ ilk açılışta bildirim izni ister)
- "yt-dlp'yi güncelle" düğmesi: site değişip indirme bozulursa motoru uygulama içinden günceller (APK'yı yeniden
  derlemek gerekmez). Hata mesajları da gerektiğinde bu düğmeyi önerir. Güncelleme internet ister.
- MP4 (kalite seç) veya MP3 (320/192/128K)
- İlerleme çubuğu, iptal
- Dosyalar `İndirilenler/VideoIndirici` klasörüne kaydedilir (Android 10+ için izin gerekmez)

## Henüz taşınmayanlar

Premium/lisans/hesap sistemi, tema seçici, Sahne Bul (yüz tarama), MP3 Stüdyo.

## Notlar

- İlk açılışta yt-dlp motoru açılır; site bozulursa yt-dlp güncellemesi gerekir
  (`YoutubeDL.getInstance().updateYoutubeDL(context)` ile uygulama içinden güncellenebilir).
- Google Play YouTube indiricilerine izin vermez; APK'yı kendi kanalından dağıtmalısın.
- Kütüphane API'si sürümler arasında küçük değişebilir. Derleme hatası olursa `app/build.gradle.kts`
  içindeki `yt` sürümünü kütüphanenin GitHub sayfasındaki en son sürümle değiştir.
