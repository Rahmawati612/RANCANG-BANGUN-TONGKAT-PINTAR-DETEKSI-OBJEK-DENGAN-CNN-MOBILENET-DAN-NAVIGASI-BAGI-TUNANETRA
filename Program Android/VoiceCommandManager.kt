package com.skripsi.smart_cane

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class VoiceState(
    val isListening : Boolean = false,
    val hasilTeks   : String  = "",
    val error       : String  = ""
)

class VoiceCommandManager(private val context: Context) {

    // Ubah menjadi var agar bisa dibongkar-pasang objeknya secara fleksibel
    private var speechRecognizer: SpeechRecognizer? = null

    private val _voiceState = MutableStateFlow(VoiceState())
    val voiceState: StateFlow<VoiceState> = _voiceState

    var onHasilDiterima: ((String) -> Unit)? = null
    var onError        : ((String) -> Unit)? = null

    fun initialize() {
        // Jika sudah diinisialisasi sebelumnya, tidak perlu mendobel objek
        if (speechRecognizer != null) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _voiceState.value = _voiceState.value.copy(
                error = "Speech recognition tidak tersedia"
            )
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    _voiceState.value = _voiceState.value.copy(
                        isListening = true,
                        error       = "",
                        hasilTeks   = ""
                    )
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val teks = matches?.firstOrNull() ?: ""
                    _voiceState.value = _voiceState.value.copy(
                        isListening = false,
                        hasilTeks   = teks
                    )
                    if (teks.isNotEmpty()) onHasilDiterima?.invoke(teks)
                }

                override fun onError(error: Int) {
                    val pesan = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH       -> "Tidak terdengar, coba lagi"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Waktu habis, coba lagi"
                        SpeechRecognizer.ERROR_NETWORK        -> "Tidak ada internet"
                        SpeechRecognizer.ERROR_AUDIO          -> "Error mikrofon"
                        else                                  -> "Gagal, coba lagi"
                    }
                    _voiceState.value = _voiceState.value.copy(
                        isListening = false,
                        error       = pesan
                    )
                    onError?.invoke(pesan)
                }

                override fun onBeginningOfSpeech()               { }
                override fun onRmsChanged(rmsdB: Float)          { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech()                     {
                    _voiceState.value = _voiceState.value.copy(isListening = false)
                }
                override fun onPartialResults(partial: Bundle?)  { }
                override fun onEvent(type: Int, params: Bundle?) { }
            })
        }
    }

    fun mulaiMendengarkan() {

        if (speechRecognizer == null) {
            initialize()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("Voice", "Gagal mulai: ${e.message}")
        }
    }

    fun berhenti() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _voiceState.value = _voiceState.value.copy(isListening = false)
    }

    fun destroyPerekam() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel() // Memaksa putus segala koneksi perekam yang menggantung
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("Voice", "Gagal destroy: ${e.message}")
        } finally {
            speechRecognizer = null // Bersihkan objek agar bisa di-initialize ulang dari nol
            _voiceState.value = _voiceState.value.copy(isListening = false)
        }
    }

    // Jaga-jaga jika activity dihancurkan bawaan kode lama
    fun destroy() {
        destroyPerekam()
    }
}