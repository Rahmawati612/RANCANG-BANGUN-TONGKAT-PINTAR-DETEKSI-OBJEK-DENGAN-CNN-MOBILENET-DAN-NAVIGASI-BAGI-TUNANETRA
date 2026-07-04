package com.skripsi.smart_cane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.speech.tts.TextToSpeech
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

data class DetectionResult(
    val className: String,
    val score: Float,
    val boundingBox: RectF
)

class ObjectDetector(private val context: Context) : TextToSpeech.OnInitListener {

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // Jarak dari sensor LiDAR (cm) yang disuapi reaktif dari Bluetooth BLE di MainActivity
    var currentLidarDistance: Float = -1f

    // status connected dari BleViewModel agar TTS tidak nyantol pas mati
    var isBleConnected: Boolean = false

    private val maxDetections = 10

    // Kunci Jeda Waktu Aman agar TTS  tidak saling tabrakan
    private var lastSpokenTime = 0L
    private val MIN_TTS_INTERVAL = 4000L // Diam selama 4 detik setelah bersuara

    // Threshold akurasi per kelas
    private val classThreshold = mapOf(
        "orang"  to 0.45f,
        "mobil"  to 0.60f,
        "motor"  to 0.60f,
        "sepeda" to 0.60f,
        "bangku" to 0.60f,
        "lubang" to 0.60f,
        "tembok" to 0.60f,
        "pohon"  to 0.40f
    )
    private val DEFAULT_THRESHOLD = 0.50f
    private val skipClasses = setOf<String>()

    companion object {
        private const val TAG               = "ObjectDetector"
        private const val MODEL_FILE        = "model.tflite"
        private const val LABEL_FILE        = "labels.txt"
        private const val INPUT_SIZE        = 300
        private const val NUM_THREADS       = 4
        private const val NMS_IOU_THRESHOLD = 0.3f
    }

    fun initialize() {
        try {
            val options = Interpreter.Options().apply { numThreads = NUM_THREADS }
            interpreter = Interpreter(loadModelFile(), options)
            labels      = loadLabels()

            // Mengembalikan inisialisasi TTS Google yang hilang
            tts = TextToSpeech(context, this)

            Log.d(TAG, "🔒 ObjectDetector Sukses Dimuat Sempurna Tanpa Typo")
        } catch (e: Exception) {
            Log.e(TAG, "❌ initialize() gagal: ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("id", "ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "❌ Bahasa Indonesia tidak didukung di HP ini")
            } else {
                isTtsReady = true
                Log.d(TAG, "✅ Asisten Suara Tongkat Pintar Siap")
            }
        }
    }

    fun detect(bitmap: Bitmap, rotationDegrees: Int = 0): List<DetectionResult> {
        val interp = interpreter ?: return emptyList()

        val prepared = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val resized = Bitmap.createScaledBitmap(prepared, INPUT_SIZE, INPUT_SIZE, true)
        val input   = bitmapToByteBuffer(resized)
        if (resized !== prepared) resized.recycle()
        if (prepared !== bitmap) prepared.recycle()

        val outNum = FloatArray(1)
        val outCls = Array(1) { FloatArray(maxDetections) }
        val outScr = Array(1) { FloatArray(maxDetections) }
        val outLoc = Array(1) { Array(maxDetections) { FloatArray(4) } }

        val outputs = mutableMapOf<Int, Any>()
        val flatIndices = mutableListOf<Int>()

        for (i in 0 until interp.outputTensorCount) {
            val shape = interp.getOutputTensor(i).shape()
            when {
                shape.size == 3 && shape[2] == 4 -> { outputs[i] = outLoc }
                shape.size == 1 || (shape.size == 2 && shape[1] == 1) -> { outputs[i] = outNum }
                shape.size == 2 && shape[0] == 1 && shape[1] > 1 -> { flatIndices.add(i) }
            }
        }

        if (flatIndices.size >= 2) {
            outputs[flatIndices[1]] = outCls
            outputs[flatIndices[0]] = outScr
        }

        try {
            interp.runForMultipleInputsOutputs(arrayOf(input), outputs)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Inferensi gagal total: ${e.message}")
            return emptyList()
        }

        val count = minOf(outNum[0].toInt(), maxDetections)
        val candidates = mutableListOf<DetectionResult>()

        for (i in 0 until count) {
            val classId = outCls[0][i].toInt()
            val score = outScr[0][i]
            Log.d(TAG, "DEBUG classId=$classId score=$score label=${labels.getOrNull(classId)}")
        }

        for (i in 0 until count) {
            val score   = outScr[0][i]
            val classId = outCls[0][i].toInt()

            val className = resolveLabel(classId) ?: continue

            if (className in skipClasses) continue
            val threshold = classThreshold[className] ?: DEFAULT_THRESHOLD

            if (score < threshold || score > 1.0f) continue

            val box    = outLoc[0][i]
            val top    = box[0].coerceIn(0f, 1f)
            val left   = box[1].coerceIn(0f, 1f)
            val bottom = box[2].coerceIn(0f, 1f)
            val right  = box[3].coerceIn(0f, 1f)

            if ((bottom - top) < 0.01f || (right - left) < 0.01f) continue

            candidates.add(DetectionResult(className, score, RectF(left, top, right, bottom)))
        }

        val finalResults = applyNMS(candidates)

        // TRIGGER SUARA KELUAR + SELEKSI AKURASI TERTINGGI
        if (finalResults.isNotEmpty()) {
            val objekPalingAkurat = finalResults.maxByOrNull { it.score }
            if (objekPalingAkurat != null) {
                speakOutput(objekPalingAkurat.className, currentLidarDistance)
            }
        } else {
            // batas pembacaan
            if (isBleConnected && currentLidarDistance > 0f && currentLidarDistance <= 300f) {
                speakLidarOnly(currentLidarDistance)
            }
        }

        return finalResults
    }

    private fun speakOutput(objectName: String, distance: Float) {
        if (!isTtsReady) return

        // Jika robot SEDANG NGOMONG, dilarang keras memotong ucapannya!
        if (tts?.isSpeaking == true) return

        val now = System.currentTimeMillis()
        // Jeda aman antar ucapan (4 detik)
        if (now - lastSpokenTime >= MIN_TTS_INTERVAL) {
            lastSpokenTime = now

            // Jika Bluetooth mati, paksa jarakSempurna jadi -1f (Bungkam suara jarak maut)
            val jarakSempurna = if (isBleConnected) currentLidarDistance else -1f

            // toleransi filter ucapan jarak AI
            val kalimat = if (jarakSempurna > 0f && jarakSempurna <= 250f) {
                "Di depan ada $objectName, dari lidar terdeteksi objek ${jarakSempurna.toInt()} centimeter"
            } else {
                "Terdeteksi ada $objectName"
            }

            tts?.speak(kalimat, TextToSpeech.QUEUE_FLUSH, null, "SMART_CANE_AUDIO")
        }
    }

    private fun speakLidarOnly(distance: Float) {
        if (!isTtsReady) return

        // Putus instan pemicu suara jika bluetooth terdeteksi mati
        if (!isBleConnected) return

        // Jangan potong suara warning yang sedang berjalan
        if (tts?.isSpeaking == true) return

        val now = System.currentTimeMillis()
        if (now - lastSpokenTime >= MIN_TTS_INTERVAL) {
            lastSpokenTime = now

            val jarakSempurna = currentLidarDistance

            if (jarakSempurna > 0f && jarakSempurna <= 250f) {
                val kalimat = "Ada halangan, dari lidar terdeteksi objek ${jarakSempurna.toInt()} centimeter"
                tts?.speak(kalimat, TextToSpeech.QUEUE_FLUSH, null, "LIDAR_ONLY_AUDIO")
            }
        }
    }

    private fun resolveLabel(classId: Int): String? {
        if (labels.isEmpty()) return null
        return labels.getOrNull(classId)
    }

    private fun applyNMS(list: List<DetectionResult>): List<DetectionResult> {
        val sorted = list.sortedByDescending { it.score }.toMutableList()
        val kept   = mutableListOf<DetectionResult>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { other ->
                other.className == best.className &&
                        iou(best.boundingBox, other.boundingBox) > NMS_IOU_THRESHOLD
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val inter = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left)) *
                maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        if (inter == 0f) return 0f
        return inter / ((a.right - a.left) * (a.bottom - a.top) +
                (b.right - b.left) * (b.bottom - b.top) - inter)
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
    }

    private fun loadLabels(): List<String> =
        context.assets.open(LABEL_FILE).bufferedReader()
            .readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buf.order(ByteOrder.nativeOrder())
        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in px) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)   // R
            buf.putFloat(((p shr 8)  and 0xFF) / 255f)   // G
            buf.putFloat((p          and 0xFF) / 255f)   // B
        }
        buf.rewind()
        return buf
    }

    fun close() {
        tts?.stop()
        tts?.shutdown()
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "ObjectDetector closed")
    }
}