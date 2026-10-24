package com.yoldash.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.util.Locale
import kotlin.math.abs

class YoldashWakeService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var awaitingCommand = false
    @Volatile private var active = false
    @Volatile private var recognizer: OfflineRecognizer? = null
    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!active) {
            active = true
            audioThread = Thread({ startOfflineListening() }, "yoldash-sherpa-audio").also { it.start() }
        }
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val azeri = engine.setLanguage(Locale("az", "AZ"))
            if (azeri == TextToSpeech.LANG_MISSING_DATA || azeri == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale("tr", "TR"))
            }
            ttsReady = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Yoldash voice assistant", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps Yoldash offline voice recognition active while the screen is locked."
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        return builder.setContentTitle("یولداش فعال است")
            .setContentText("تشخیص گفتار آفلاین فعال است؛ بگویید «یولداش».")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp).setOngoing(true).build()
    }

    private fun startOfflineListening() {
        try {
            val modelDir = "sherpa-whisper-tiny"
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "$modelDir/tiny-encoder.int8.onnx",
                        decoder = "$modelDir/tiny-decoder.int8.onnx",
                        language = "",
                        task = "transcribe",
                        tailPaddings = 0
                    ),
                    tokens = "$modelDir/tiny-tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "whisper"
                )
            )
            recognizer = OfflineRecognizer(assetManager = assets, config = config)
        } catch (e: Exception) {
            handler.post { speak("بارگذاری مدل آفلاین ناموفق بود.") }
            active = false
            stopSelf()
            return
        }

        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            handler.post { speak("میکروفون در دسترس نیست.") }
            active = false
            stopSelf()
            return
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, sampleRate * 2)
            )
        } catch (e: Exception) {
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            handler.post { speak("راه‌اندازی میکروفون ناموفق بود.") }
            active = false
            stopSelf()
            return
        }
        audioRecord = recorder
        val readBuffer = ShortArray(2048)
        val utterance = ArrayList<Float>(sampleRate * 6)
        var speechStarted = false
        var quietSamples = 0
        val quietLimit = sampleRate * 8 / 10
        val maxSamples = sampleRate * 6
        try {
            recorder.startRecording()
            while (active) {
                val count = recorder.read(readBuffer, 0, readBuffer.size)
                if (count <= 0) continue
                var energy = 0L
                for (i in 0 until count) energy += abs(readBuffer[i].toInt())
                val average = energy / count
                if (average > 420) {
                    speechStarted = true
                    quietSamples = 0
                } else if (speechStarted) {
                    quietSamples += count
                }
                if (speechStarted) {
                    for (i in 0 until count) utterance.add(readBuffer[i] / 32768.0f)
                }
                if (speechStarted && (quietSamples >= quietLimit || utterance.size >= maxSamples)) {
                    val audio = utterance.toFloatArray()
                    utterance.clear()
                    speechStarted = false
                    quietSamples = 0
                    if (audio.size >= sampleRate / 2) transcribe(audio, sampleRate)
                }
            }
        } catch (_: Exception) {
            if (active) handler.post { speak("تشخیص آفلاین متوقف شد.") }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            audioRecord = null
            recognizer?.release()
            recognizer = null
        }
    }

    private fun transcribe(samples: FloatArray, sampleRate: Int) {
        try {
            val engine = recognizer ?: return
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                engine.decode(stream)
                val phrase = engine.getResult(stream).text.trim()
                if (phrase.isNotBlank()) handler.post { handlePhrase(phrase) }
            } finally {
                stream.release()
            }
        } catch (_: Exception) {
            // Keep the microphone loop alive if one utterance fails to decode.
        }
    }

    private fun handlePhrase(phrase: String) {
        if (phrase.isBlank()) return
        val normalized = phrase.lowercase(Locale.ROOT)
        val wakeWords = listOf("yoldaş", "yoldash", "yoldas", "یولداش", "yoldaşım")
        val wakeWord = wakeWords.firstOrNull { normalized.contains(it) }
        if (wakeWord != null) {
            val remainder = normalized.substringAfter(wakeWord).trim()
            awaitingCommand = remainder.isBlank()
            sendRecognizedPhrase(phrase)
            speak("Buyur.")
        } else if (awaitingCommand) {
            sendRecognizedPhrase(phrase)
            speak("Oldu.")
            awaitingCommand = false
        }
    }

    private fun sendRecognizedPhrase(phrase: String) {
        val intent = Intent(ACTION_RECOGNIZED).apply {
            setPackage(packageName)
            putExtra(EXTRA_PHRASE, phrase)
        }
        sendBroadcast(intent)
    }

    private fun speak(message: String) {
        if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "yoldash-wake")
    }

    override fun onDestroy() {
        active = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioThread?.interrupt()
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.yoldash.app.action.START_WAKE"
        const val ACTION_STOP = "com.yoldash.app.action.STOP_WAKE"
        const val ACTION_RECOGNIZED = "com.yoldash.app.action.RECOGNIZED_PHRASE"
        const val EXTRA_PHRASE = "com.yoldash.app.extra.PHRASE"
        private const val CHANNEL_ID = "yoldash_wake_service"
        private const val NOTIFICATION_ID = 601
    }
}
