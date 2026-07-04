package com.skripsi.smart_cane

import android.graphics.Matrix
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// ─────────────────────────────────────────────────────────────────────────────
// MAIN COMPOSABLE
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeteksiScreen(
    onBack         : () -> Unit,
    bleViewModel   : BleViewModel,
    objectDetector : ObjectDetector
) {
    // ── BLE state ─────────────────────────────────────────────────────────────
    val distance   by bleViewModel.distance.collectAsState()
    val connected  by bleViewModel.connected.collectAsState()
    val alertLevel by bleViewModel.alertLevel.collectAsState()
    val jarakLidar = if (connected && distance > 0 && distance < 5000) "${distance / 10} cm" else "-- cm"
    val isBahaya   = alertLevel == AlertLevel.DANGER

    // AtomicInteger agar jarak selalu fresh di semua thread
    val jarakTerkiniMm = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    LaunchedEffect(distance) { jarakTerkiniMm.set(distance) }

    // ── Deteksi state ─────────────────────────────────────────────────────────
    var isAktif          by remember { mutableStateOf(false) }
    var detectionResults by remember { mutableStateOf<List<DetectionResult>>(emptyList()) }
    var statusTeks       by remember { mutableStateOf("Tekan Mulai untuk mengaktifkan kamera") }
    var lensFacing       by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }

    // ── Context & lifecycle ───────────────────────────────────────────────────
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope          = rememberCoroutineScope()
    val activity       = remember { context as MainActivity }

    // ── SEPARASI PENGATURAN MULTI-CHANNEL INTERRUPT TTS ──────────────────────
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady  by remember { mutableStateOf(false) }

    val lastSpokenNavigasi  = remember { AtomicLong(0L) }
    val TTS_INTERVAL_NAVIGASI = 1500L

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = engine!!.setLanguage(Locale("id", "ID"))
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    engine!!.language = Locale.US
                }
                engine!!.setSpeechRate(0.9f)
                engine!!.setPitch(1.0f)
                ttsEngine = engine
                ttsReady  = true
            }
        }
        onDispose {
            engine?.stop()
            engine?.shutdown()
            ttsReady = false

            activity.onVolumeUp = null
            activity.onVolumeUpDouble = null
            activity.onVolumeDown = null
            activity.onVolumeDownDouble = null
        }
    }

    fun terjemahkanArah(text: String): String {
        return text.lowercase()
            .replace("turn left", "belok kiri")
            .replace("turn right", "belok kanan")
            .replace("slight left", "agak serong kiri")
            .replace("slight right", "agak serong kanan")
            .replace("sharp left", "belok tajam ke kiri")
            .replace("sharp right", "belok tajam ke kanan")
            .replace("go straight", "lurus terus")
    }

    fun speakNavigasi(text: String) {
        if (!ttsReady) return
        val terjemahan = terjemahkanArah(text)
        if (terjemahan.isBlank()) return
        val now = System.currentTimeMillis()

        if (now - lastSpokenNavigasi.get() >= TTS_INTERVAL_NAVIGASI) {
            lastSpokenNavigasi.set(now)
            ttsEngine?.speak(terjemahan, TextToSpeech.QUEUE_FLUSH, null, "nav_${now}")
        }
    }

    // ── Navigasi & Voice ──────────────────────────────────────────────────────
    val navigasiManager = remember { NavigasiManager(context) }
    val navState        by navigasiManager.navState.collectAsState()
    val lokasi          by navigasiManager.lokasiSaatIni.collectAsState()

    val voiceManager = remember { VoiceCommandManager(context) }
    val voiceState   by voiceManager.voiceState.collectAsState()

    // ── LOGIKA UTAMA SINKRONISASI TOMBOL DAN JEDA AMAN MIC ───────────────────
    LaunchedEffect(Unit) {
        navigasiManager.startGPS()
        voiceManager.initialize()

        navigasiManager.onInstruksiBaru = { instruksi ->
            speakNavigasi(instruksi)
        }
        navigasiManager.startGPS()
        FirebaseUploader.mulai(context)   // Mulai timer Firebase uploader

        // KELOMPOK A: TOMBOL VOLUME ATAS KELOLA SENSOR KAMERA AI
        activity.onVolumeUp = {
            if (!isAktif) {
                isAktif = true
                speakNavigasi("Deteksi aktif")
            }
        }

        activity.onVolumeUpDouble = {
            if (isAktif) {
                isAktif = false
                speakNavigasi("Deteksi dimatikan")
            }
        }

        // KELOMPOK B: TOMBOL VOLUME BAWAH KELOLA NAVIGASI & VOICE COMMAND
        activity.onVolumeDown = {
            if (!navState.isNavigating) {
                scope.launch {
                    speakNavigasi("Sebutkan tujuan Anda")
                    // Jeda agar ucapan selesai sebelum microphone aktif mendengarkan rute
                    delay(1800)
                    voiceManager.mulaiMendengarkan()
                }
            } else {
                speakNavigasi(navState.instruksiSaatIni)
            }
        }

        activity.onVolumeDownDouble = {
            if (navState.isNavigating) {
                navigasiManager.stopNavigasi()
                speakNavigasi("Navigasi dihentikan")
            }
        }

        voiceManager.onHasilDiterima = { teks ->
            scope.launch {
                val namaLokasi = TujuanParser.ekstrakTujuan(teks)
                speakNavigasi("Mencari $namaLokasi")
                TujuanParser.cariLokasi(
                    namaLokasi = namaLokasi,
                    kotaBias   = "Malang"
                ) { hasil ->
                    scope.launch(Dispatchers.Main) {
                        if (hasil.ditemukan) {
                            speakNavigasi("Ditemukan. Menghitung rute ke $namaLokasi")
                            navigasiManager.mintaRute(
                                namaLokasi, hasil.lat, hasil.lon
                            ) { instruksi ->  scope.launch {
                                delay(2000)
                                speakNavigasi(instruksi)
                            }
                            }
                        } else {
                            speakNavigasi("Maaf, $namaLokasi tidak ditemukan. Tekan volume bawah satu kali untuk coba lagi")
                        }
                    }
                }
            }
        }
        delay(2000)
        speakNavigasi("Deteksi siap. Tekan volume atas untuk mulai deteksi. Tekan volume bawah untuk memulai navigasi")


        //  Memanggil destroyPerekam() agar sistem microphone putus total saat error
        voiceManager.onError = { error ->
            scope.launch {
                // 1. Langsung hancurkan engine perekam yang hang gantung di RAM HP
                voiceManager.destroyPerekam()

                // 2. Tentukan pesan berdasarkan jenis errornya
                val pesanError = when {
                    error.contains("timeout", ignoreCase = true) || error.contains("No speech", ignoreCase = true) ->
                        "Tidak terdengar suara. Silakan tekan volume bawah satu kali untuk mencoba lagi."
                    else ->
                        "Gagal mengenali suara. Silakan tekan volume bawah satu kali untuk mencoba lagi."
                }

                // 3. Suarakan panduan instruksi ke tunanetra dengan aman
                speakNavigasi(pesanError)
            }
        }
    }

    // ── Camera executor ───────────────────────────────────────────────────────
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // ── Kamera LaunchedEffect Analyzer ────────────────────────────────────────
    LaunchedEffect(isAktif, lensFacing) {
        val cameraProvider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }

        if (!isAktif) {
            cameraProvider.unbindAll()
            detectionResults = emptyList()
            statusTeks = "Deteksi dihentikan"
            return@LaunchedEffect
        }

        statusTeks = "Memulai kamera..."

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isAktif) { imageProxy.close(); return@setAnalyzer }

            val bitmap          = imageProxy.toBitmapSafe()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()

            if (bitmap == null) return@setAnalyzer

            val results: List<DetectionResult> = try {

                objectDetector.isBleConnected = connected

                objectDetector.detect(bitmap, rotationDegrees)
            } catch (e: Exception) {
                Log.e("DeteksiScreen", "Deteksi error: ${e.message}")
                emptyList()
            } finally {
                bitmap.recycle()
            }

            scope.launch(Dispatchers.Main) {
                detectionResults = results
                statusTeks = if (results.isEmpty()) "Memindai..." else "Terdeteksi ${results.size} objek"

                if (results.isNotEmpty()) {
                    val priority = results.firstOrNull { it.className == "lubang" }
                        ?: results.firstOrNull { it.className == "orang" }
                        ?: results.firstOrNull { it.className in listOf("motor", "mobil") }
                        ?: results.first()

                    // ── SINKRONISASI UPDATE DATA BACKGROUND FIREBASE ──
                    val lokasiGps = lokasi
                    if (lokasiGps != null) {
                        val jarakFresh = jarakTerkiniMm.get()
                        val jarakStr   = if (connected && jarakFresh > 0) "${jarakFresh / 10} cm" else "-- cm"

                        FirebaseUploader.updateSnapshot(
                            objek   = priority.className,
                            jarakCm = jarakStr,
                            lat     = lokasiGps.latitude,
                            lng     = lokasiGps.longitude,
                            context = context
                        )
                    }
                }
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                preview,
                imageAnalysis
            )
            statusTeks = "Kamera aktif – memindai..."
        } catch (e: Exception) {
            Log.e("DeteksiScreen", "Gagal bind kamera: ${e.message}")
            statusTeks = "Gagal membuka kamera"
            isAktif = false
        }
    }

    // ── UI INTERFACE JETPACK COMPOSE ──────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFBBCEE4))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            IconButton(
                onClick = { isAktif = false; onBack() },
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF1A237E))
            }
            Text(
                text       = "Smart Cane – Deteksi",
                color      = Color(0xFF1A237E),
                fontWeight = FontWeight.Bold,
                fontSize   = 16.sp,
                modifier   = Modifier.align(Alignment.Center)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {

            // PANEL KAMERA VISUAL
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C1C)),
                contentAlignment = Alignment.Center
            ) {
                if (!isAktif) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📷", fontSize = 52.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text      = "Tekan Tombol Volume Atas 1x\nuntuk menyalakan kamera",
                            color     = Color(0xFFAAAAAA),
                            fontSize  = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val lebarCanvas  = size.width
                        val tinggiCanvas = size.height

                        detectionResults.forEach { result ->
                            val kiri  = result.boundingBox.left * lebarCanvas
                            val atas  = result.boundingBox.top * tinggiCanvas
                            val kanan = result.boundingBox.right * lebarCanvas
                            val bawah = result.boundingBox.bottom * tinggiCanvas

                            val warnaKotak = when (result.className.lowercase()) {
                                "lubang"        -> Color(0xFFD32F2F)
                                "orang"         -> Color(0xFFF57F17)
                                "tembok"        -> Color(0xFFFBC02D)
                                "pohon", "tree" -> Color(0xFF388E3C)
                                else            -> Color(0xFF1976D2)
                            }

                            drawRect(
                                color   = warnaKotak,
                                topLeft = androidx.compose.ui.geometry.Offset(kiri, atas),
                                size    = androidx.compose.ui.geometry.Size(kanan - kiri, bawah - atas),
                                style   = Stroke(width = 6f)
                            )

                            drawIntoCanvas { canvas ->
                                val paint = android.graphics.Paint().apply {
                                    color     = android.graphics.Color.WHITE
                                    textSize  = 34f
                                    style     = android.graphics.Paint.Style.FILL
                                    setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
                                }
                                canvas.nativeCanvas.drawText(
                                    "${result.className} ${(result.score * 100).toInt()}%",
                                    kiri + 8f, atas + 35f, paint
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                CameraSelector.LENS_FACING_FRONT
                            else
                                CameraSelector.LENS_FACING_BACK
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Ganti Lensa", tint = Color.White)
                    }

                    Box(
                        modifier        = Modifier.fillMaxSize().padding(8.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xCC000000)),
                                shape  = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text     = statusTeks,
                                    color    = Color(0xFF4CAF50),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            detectionResults.take(4).forEach { DetectionChip(it) }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // PANEL MONITORING JALUR NAVIGASI MAP
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1A237E), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            text       = "🗺️ Navigasi",
                            color      = Color(0xFF4FC3F7),
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text     = if (lokasi != null) "📡 GPS Aktif" else "📡 Mencari GPS...",
                            color    = if (lokasi != null) Color(0xFF4CAF50) else Color(0xFFAAAAAA),
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    when {
                        navState.isNavigating -> {
                            Card(
                                colors   = CardDefaults.cardColors(Color(0xFF0D47A1)),
                                shape    = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text       = terjemahkanArah(navState.instruksiSaatIni),
                                        color      = Color.White,
                                        fontSize   = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text     = "Sisa: ${navState.jarakKeTujuan}",
                                        color    = Color(0xFF90CAF9),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick  = {
                                    navigasiManager.stopNavigasi()
                                    speakNavigasi("Navigasi dihentikan")
                                },
                                colors   = ButtonDefaults.buttonColors(Color(0xFFD32F2F)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("⏹ Stop Navigasi (Vol Down 2x)", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        navState.sudahSampai -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier            = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text       = "✅ Anda telah tiba di tujuan!",
                                    color      = Color(0xFF4CAF50),
                                    fontSize   = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick  = { navigasiManager.stopNavigasi() },
                                    colors   = ButtonDefaults.buttonColors(Color(0xFF1565C0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("🔄 Navigasi Baru", color = Color.White)
                                }
                            }
                        }

                        navState.isLoading -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier            = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator(
                                    color    = Color(0xFF4FC3F7),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text     = navState.instruksiSaatIni,
                                    color    = Color(0xFFAAAAAA),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        else -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier            = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text      = if (voiceState.isListening)
                                        "🎤 Mendengarkan..."
                                    else
                                        "Tekan Volume Bawah 1x untuk mendikte rute\nContoh: \"politeknik negeri malang\"",
                                    color     = if (voiceState.isListening)
                                        Color(0xFF4CAF50) else Color(0xFFAAAAAA),
                                    fontSize  = 12.sp,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        if (voiceState.isListening) {
                                            voiceManager.destroyPerekam()
                                        } else {
                                            scope.launch {
                                                speakNavigasi("Sebutkan tujuan Anda")
                                                delay(1800)
                                                voiceManager.mulaiMendengarkan()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (voiceState.isListening)
                                            Color(0xFFD32F2F) else Color(0xFF1565C0)
                                    ),
                                    shape    = CircleShape,
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Text(
                                        text     = if (voiceState.isListening) "⏹" else "🎤",
                                        fontSize = 24.sp
                                    )
                                }

                                if (voiceState.hasilTeks.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text     = "\"${voiceState.hasilTeks}\"",
                                        color    = Color(0xFF90CAF9),
                                        fontSize = 11.sp
                                    )
                                }

                                if (voiceState.error.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text     = voiceState.error,
                                        color    = Color(0xFFEF9A9A),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // PANEL ALIRAN INDIKATOR WARNING DATA LIDAR SENSOR
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isBahaya) Color(0xFFB71C1C) else Color(0xFF212121)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠️", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text     = "Peringatan Jarak",
                                color    = Color(0xFFAAAAAA),
                                fontSize = 11.sp
                            )
                            Text(
                                text = when {
                                    !connected -> "Sistem tidak aktif"
                                    isBahaya   -> "Objek terlalu dekat!"
                                    else       -> "Sistem aktif memantau"
                                },
                                color      = Color.White,
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("📡 LiDAR", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                        Text(
                            text       = jarakLidar,
                            color      = if (isBahaya) Color.Yellow else Color.White,
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick  = { isAktif = !isAktif },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAktif) Color(0xFFD32F2F) else Color(0xFF1565C0)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text       = if (isAktif) "⏹ STOP DETEKSI (Vol Up 2x)" else "▶ MULAI DETEKSI (Vol Up 1x)",
                        fontSize   = 14.sp,
                        color      = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun DetectionChip(result: DetectionResult) {
    val chipColor = when (result.className.lowercase()) {
        "lubang"         -> Color(0xCCB71C1C)
        "orang"          -> Color(0xCCF57F17)
        "mobil", "motor" -> Color(0xCCB71C1C)
        else             -> Color(0xCC1B5E20)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = chipColor),
        shape  = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text       = result.className.replaceFirstChar { it.uppercase() },
                color      = Color.White,
                fontSize   = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text     = "${(result.score * 100).toInt()}%",
                color    = Color(0xFFEEEEEE),
                fontSize = 11.sp
            )
        }
    }
}

private fun ImageProxy.toBitmapSafe(): android.graphics.Bitmap? {
    return try {
        val plane       = planes[0]
        val rowStride   = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer      = plane.buffer

        val bmp = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888
        )

        if (rowStride == width * pixelStride) {
            bmp.copyPixelsFromBuffer(buffer)
        } else {
            val rowBytes = width * pixelStride
            val rowData  = ByteArray(rowStride)
            val pixelBuf = java.nio.ByteBuffer.allocate(width * height * 4)
            repeat(height) {
                buffer.get(rowData, 0, rowStride)
                pixelBuf.put(rowData, 0, rowBytes)
            }
            pixelBuf.rewind()
            bmp.copyPixelsFromBuffer(pixelBuf)
        }
        bmp
    } catch (e: Exception) {
        Log.e("DeteksiScreen", "toBitmapSafe gagal: ${e.message}")
        null
    }
}