package com.videoindirici.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.launch

// ---- Masaüstündeki "Gece Mavisi" temasına yakın renkler
private val Bg = Color(0xFF060A16)
private val Kart = Color(0xFF0D1424)
private val Girdi = Color(0xFF0A1020)
private val Kenar = Color(0xFF212C48)
private val Yazi = Color(0xFFE8ECF8)
private val Soluk = Color(0xFF8A93B2)
private val Parlak = Color(0xFF22D3EE)
private val Hata = Color(0xFFFF453A)
private val Gradyan = Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF06B6D4), Color(0xFFA855F7)))

class VM : ViewModel() {
    var link by mutableStateOf("")
    var analiz by mutableStateOf(false)
    var bilgi by mutableStateOf<Bilgi?>(null)
    var video by mutableStateOf(true)
    var secim by mutableIntStateOf(0)
    // İndirme artık DownloadService'te çalışır; durum DlDurum'dan okunur (uygulama kapansa da sürer)
    val indiriyor get() = DlDurum.aktif
    val yuzde get() = DlDurum.yuzde
    private var yerelDurum by mutableStateOf("Hazır")
    var durum: String
        get() = DlDurum.durum ?: yerelDurum
        set(v) { if (!DlDurum.aktif) DlDurum.durum = null; yerelDurum = v }
    var mesaj: String?
        get() = DlDurum.mesaj
        set(v) { DlDurum.mesaj = v }
    var tumListe by mutableStateOf(false)          // "Tüm listeyi indir" anahtarı
    var secili by mutableStateOf<Set<Int>?>(null)  // null = listedeki hepsi
    var secimAcik by mutableStateOf(false)
    fun analizEt() {
        val l = link.trim()
        if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(l)) {
            mesaj = "Geçerli bir http(s) linki girin."; return
        }
        if (!App.hazir) { mesaj = "Motor başlatılamadı: ${App.hata ?: "bekleyin"}"; return }
        analiz = true; durum = "Video bilgisi alınıyor…"; bilgi = null
        viewModelScope.launch {
            try {
                val b = Indirici.analiz(l)
                bilgi = b; secim = 0; video = true; secili = null
                tumListe = b.liste?.sadece == true      // sadece liste linkiyse varsayılan: tüm liste
                durum = "Hazır — kaliteyi seç ve indir"
            } catch (e: Exception) {
                durum = "Hata"
                mesaj = (e.message ?: "Video bilgisi alınamadı.").lines().last { it.isNotBlank() }.take(300) + hataIpucu(e.message)
            } finally { analiz = false }
        }
    }

    fun indir(ctx: android.content.Context) {
        val b = bilgi ?: return
        if (indiriyor) return
        if (tumListe && b.liste != null && secili?.isEmpty() == true) {
            mesaj = "Listeden en az bir video seç."; return
        }
        if (!App.hazir) { mesaj = "Motor başlatılamadı: ${App.hata ?: "bekleyin"}"; return }
        DlDurum.aktif = true; DlDurum.yuzde = 0f; DlDurum.durum = "İndirme başlatılıyor…"; DlDurum.mesaj = null
        DownloadService.baslat(
            ctx.applicationContext,
            DlIs(b, video, b.kaliteler.getOrNull(secim), SES_KALITELER.getOrNull(secim), tumListe, secili),
        )
    }

    // ---- yt-dlp motorunu uygulama içinden güncelle (siteler değişince indirme bozulursa APK'yı yeniden derlemeden düzeltir)
    var ytSurum by mutableStateOf("")
    var guncelleniyor by mutableStateOf(false)

    fun surumOku(ctx: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ytSurum = try { YoutubeDL.getInstance().version(ctx.applicationContext) ?: "" } catch (_: Exception) { "" }
        }
    }

    fun guncelle(ctx: android.content.Context) {
        if (guncelleniyor) return
        if (indiriyor) { mesaj = "Devam eden bir indirme var, bitmesini bekleyin."; return }
        if (!App.hazir) { mesaj = "Motor başlatılamadı: ${App.hata ?: "bekleyin"}"; return }
        guncelleniyor = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val s = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                ytSurum = try { YoutubeDL.getInstance().version(ctx.applicationContext) ?: ytSurum } catch (_: Exception) { ytSurum }
                mesaj = when (s) {
                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp güncellendi."
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp zaten güncel."
                    else -> "Güncelleme tamamlandı."
                }
            } catch (e: Exception) {
                mesaj = "Güncelleme başarısız: " + (e.message ?: "internet bağlantısını kontrol et").lines().first().take(200)
            } finally { guncelleniyor = false }
        }
    }

    fun iptal(ctx: android.content.Context) {
        DownloadService.iptal(ctx.applicationContext)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+: ilerleme bildirimi için izin iste (reddedilirse indirme yine çalışır, bildirim görünmez)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Kart, primary = Parlak)) {
                val vm: VM = viewModel()
                LaunchedEffect(Unit) { paylasilanLink(intent)?.let { vm.link = it; vm.analizEt() } }
                Ekran(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun paylasilanLink(i: Intent?): String? =
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let {
            Regex("https?://\\S+").find(it)?.value
        } else null
}

@Composable
fun Ekran(vm: VM) {
    val ctx = LocalContext.current
    val pano = LocalClipboardManager.current
    LaunchedEffect(Unit) { vm.surumOku(ctx) }
    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Video İndirici", color = Yazi, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Linki yapıştır, kaliteyi seç, indir.", color = Soluk, fontSize = 13.sp)

        // Link kutusu
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Kart),
            border = BorderStroke(1.dp, Kenar)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = vm.link, onValueChange = { vm.link = it }, singleLine = true,
                    placeholder = { Text("https://…  video linki", color = Soluk) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Parlak, unfocusedBorderColor = Kenar,
                        focusedTextColor = Yazi, unfocusedTextColor = Yazi,
                        focusedContainerColor = Girdi, unfocusedContainerColor = Girdi),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pano.getText()?.text?.trim()?.let { if (it.isNotEmpty()) vm.link = it } },
                        modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(13.dp),
                    ) { Text("Yapıştır") }
                    GradyanDugme("Analiz Et", !vm.analiz && !vm.indiriyor, Modifier.weight(1f)) { vm.analizEt() }
                }
            }
        }

        if (vm.analiz) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Parlak)

        vm.bilgi?.let { b ->
            // Video bilgisi
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Kart),
                border = BorderStroke(1.dp, Kenar)) {
                Column(Modifier.padding(14.dp)) {
                    Box {
                        AsyncImage(b.resim, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop)
                        sureYaz(b.sureSn).takeIf { it.isNotEmpty() }?.let {
                            Text(it, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                                    .background(Color(0xCC070B16), RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(b.baslik, color = Yazi, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (b.kanal.isNotEmpty()) Text(b.kanal, color = Soluk, fontSize = 12.sp)
                }
            }

            // Oynatma listesi
            b.liste?.let { ls ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Kart),
                    border = BorderStroke(1.dp, Kenar)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(vm.tumListe, { vm.tumListe = it }, enabled = !vm.indiriyor,
                                colors = SwitchDefaults.colors(checkedTrackColor = Parlak))
                            Spacer(Modifier.width(12.dp))
                            val n = vm.secili?.size
                            Text(
                                when {
                                    vm.tumListe && n != null -> "Listeden $n / ${ls.videolar.size} video seçili"
                                    ls.sadece -> "Oynatma listesi: ${ls.adet} video — tüm listeyi indir"
                                    else -> "Bu videonun oynatma listesi var (${ls.adet} video) — tüm listeyi indir"
                                },
                                color = Soluk, fontSize = 13.sp, modifier = Modifier.weight(1f),
                            )
                        }
                        if (vm.tumListe) {
                            OutlinedButton({ vm.secimAcik = true }, Modifier.fillMaxWidth(), enabled = !vm.indiriyor,
                                shape = RoundedCornerShape(12.dp)) { Text("Videoları seç…") }
                        }
                    }
                }
            }

            // Format + kalite
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(Kart),
                border = BorderStroke(1.dp, Kenar)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Kalite & Format", color = Yazi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Video (MP4)", "Ses (MP3)").forEachIndexed { i, ad ->
                            SegmentedButton(selected = (i == 0) == vm.video, onClick = { vm.video = i == 0; vm.secim = 0 },
                                shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(ad) }
                        }
                    }
                    val ogeler: List<Triple<String, String, String>> =
                        if (vm.video) b.kaliteler.map { Triple(it.ad, "MP4", boyutYaz(it.boyut)) }
                        else SES_KALITELER.map { Triple(it.ad, "MP3 · ${it.kbps}K", boyutYaz(b.sureSn * it.kbps * 125.0)) }
                    ogeler.forEachIndexed { i, (ad, fmt, boyut) ->
                        val sec = i == vm.secim
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (sec) Parlak.copy(alpha = .10f) else Girdi)
                                .clickable { vm.secim = i }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(sec, { vm.secim = i }, colors = RadioButtonDefaults.colors(selectedColor = Parlak))
                            Column(Modifier.weight(1f)) {
                                Text(ad, color = Yazi, fontWeight = FontWeight.Bold)
                                Text(fmt, color = Soluk, fontSize = 12.sp)
                            }
                            Text(boyut, color = Parlak, fontSize = 12.sp)
                        }
                    }
                }
            }

            if (vm.indiriyor) {
                LinearProgressIndicator(progress = { vm.yuzde / 100f }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Parlak, trackColor = Kenar)
                OutlinedButton({ vm.iptal(ctx) },Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, Hata)) { Text("İptal", color = Hata) }
            } else {
                GradyanDugme(if (vm.video) "↓  Videoyu İndir" else "↓  Sesi İndir", true, Modifier.fillMaxWidth(), 54.dp) { vm.indir(ctx) }
            }
        }

        Text(vm.durum, color = Soluk, fontSize = 12.sp)

        // Motor sürümü + güncelleme
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.ytSurum.isNotEmpty()) "yt-dlp sürümü: ${vm.ytSurum}" else "yt-dlp",
                color = Soluk, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.guncelle(ctx) }, enabled = !vm.guncelleniyor && !vm.indiriyor) {
                if (vm.guncelleniyor) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Parlak)
                    Spacer(Modifier.width(8.dp))
                    Text("Güncelleniyor…")
                } else Text("yt-dlp'yi güncelle")
            }
        }
    }

    val ls = vm.bilgi?.liste
    if (vm.secimAcik && ls != null) {
        SecimDialog(
            videolar = ls.videolar,
            baslangic = vm.secili ?: ls.videolar.map { it.no }.toSet(),
            kapat = { vm.secimAcik = false },
            tamam = { s ->
                vm.secili = if (s.size == ls.videolar.size) null else s
                vm.secimAcik = false
            },
        )
    }

    vm.mesaj?.let {
        AlertDialog(onDismissRequest = { vm.mesaj = null }, containerColor = Kart,
            confirmButton = { TextButton({ vm.mesaj = null }) { Text("Tamam") } },
            text = { Text(it, color = Yazi) })
    }
}

/** Oynatma listesinden video seçme penceresi (onay kutusu + sıra + başlık + süre). */
@Composable
fun SecimDialog(videolar: List<ListeVideo>, baslangic: Set<Int>, kapat: () -> Unit, tamam: (Set<Int>) -> Unit) {
    var sec by remember { mutableStateOf(baslangic) }
    AlertDialog(
        onDismissRequest = kapat, containerColor = Kart,
        title = { Text("Videoları seç  (${sec.size} / ${videolar.size})", color = Yazi, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row {
                    TextButton({ sec = videolar.map { it.no }.toSet() }) { Text("Hepsini seç") }
                    TextButton({ sec = emptySet() }) { Text("Hiçbirini seçme") }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    itemsIndexed(videolar) { _, v ->
                        val acik = v.no in sec
                        Row(
                            Modifier.fillMaxWidth().clickable { sec = if (acik) sec - v.no else sec + v.no }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(acik, { sec = if (it) sec + v.no else sec - v.no },
                                colors = CheckboxDefaults.colors(checkedColor = Parlak))
                            Text("%02d".format(v.no), color = Soluk, fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(v.baslik, color = if (acik) Yazi else Soluk, fontSize = 13.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            sureYaz(v.sureSn).takeIf { it.isNotEmpty() }?.let {
                                Spacer(Modifier.width(8.dp))
                                Text(it, color = Soluk, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ tamam(sec) }) { Text("Tamam") } },
        dismissButton = { TextButton(kapat) { Text("Vazgeç") } },
    )
}

@Composable
fun GradyanDugme(metin: String, aktif: Boolean, modifier: Modifier = Modifier, h: androidx.compose.ui.unit.Dp = 48.dp, onClick: () -> Unit) {
    Box(
        modifier.height(h).clip(RoundedCornerShape(13.dp))
            .background(if (aktif) Gradyan else Brush.horizontalGradient(listOf(Kenar, Kenar)))
            .clickable(enabled = aktif, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(metin, color = if (aktif) Color.White else Soluk, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}
