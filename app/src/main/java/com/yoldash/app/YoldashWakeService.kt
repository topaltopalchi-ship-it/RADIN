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
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class YoldashWakeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    @Volatile private var awaitingCommand = false
    @Volatile private var serviceStopping = false
    @Volatile private var ttsReady = false
    private var tts: android.speech.tts.TextToSpeech? = null
    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    val result = engine.setLanguage(Locale("az", "AZ"))
                    if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                        result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Azerbaijani TTS is unavailable; Turkish fallback is disabled.")
                    }
                    ttsReady = true
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceStopping = true
            active.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        serviceStopping = false
        startForeground(NOTIFICATION_ID, buildNotification())
        if (active.compareAndSet(false, true)) {
            listenThread = thread(name = "YoldashSherpaMic") { runOfflineRecognition() }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Yoldash voice assistant", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Offline Sherpa-ONNX voice wake-up."
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
            .setContentText("تشخیص گفتار آفلاین با Sherpa؛ بگویید «یولداش».")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp).setOngoing(true).build()
    }

    private fun modelFile(assetName: String): String {
        val directory = File(filesDir, "sherpa-whisper-tiny")
        if (!directory.exists()) directory.mkdirs()
        val destination = File(directory, assetName)
        if (!destination.exists() || destination.length() == 0L) {
            assets.open("sherpa-whisper-tiny/$assetName").use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        }
        return destination.absolutePath
    }

    private fun runOfflineRecognition() {
        try {
            val whisper = OfflineWhisperModelConfig(
                encoder = modelFile("tiny-encoder.int8.onnx"),
                decoder = modelFile("tiny-decoder.int8.onnx"),
                language = "fa",
                task = "transcribe",
                tailPaddings = 0
            )
            val model = OfflineModelConfig(
                whisper = whisper,
                tokens = modelFile("tiny-tokens.txt"),
                numThreads = 4,
                provider = "cpu",
                modelType = "whisper"
            )
            val config = OfflineRecognizerConfig(
                modelConfig = model,
                decodingMethod = "greedy_search"
            )
            recognizer = OfflineRecognizer(config = config)

            val sampleRate = 16000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("AudioRecord buffer unavailable: $minBuffer")
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, sampleRate * 2)
            )
            audioRecord = recorder
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone initialization failed")
            recorder.startRecording()
            speak("یولداش فعال شد.")
            val chunkSamples = sampleRate * 2
            val input = ShortArray(2048)
            val chunk = ShortArray(chunkSamples)
            var filled = 0
            while (active.get() && !Thread.currentThread().isInterrupted) {
                val count = recorder.read(input, 0, input.size)
                if (count <= 0) {
                    Log.w(TAG, "AudioRecord read returned $count")
                    continue
                }
                var offset = 0
                while (offset < count) {
                    val copyCount = minOf(count - offset, chunkSamples - filled)
                    System.arraycopy(input, offset, chunk, filled, copyCount)
                    filled += copyCount
                    offset += copyCount
                    if (filled == chunkSamples) {
                        decodeChunk(chunk, sampleRate)
                        filled = 0
                    }
                }
            }
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Sherpa offline recognition failed", e)
            handler.post { speak("تشخیص گفتار آفلاین راه‌اندازی نشد.") }
        } finally {
            active.set(false)
            // Restart if recognition exits unexpectedly instead of leaving the service deaf.
            if (!serviceStopping) {
                handler.postDelayed({
                    if (!serviceStopping && !active.get() &&
                        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "Restarting offline recognition after loop exit")
                        if (active.compareAndSet(false, true)) {
                            listenThread = thread(name = "YoldashSherpaMic") { runOfflineRecognition() }
                        }
                    }
                }, 1500L)
            }
        }
    }

    private fun decodeChunk(pcm: ShortArray, sampleRate: Int) {
        val engine = recognizer ?: return
        // Skip near-silent chunks; Whisper decoding silence is expensive and adds variable response delay.
        var energy = 0.0
        for (sample in pcm) {
            val value = sample / 32768.0
            energy += value * value
        }
        val rms = kotlin.math.sqrt(energy / pcm.size)
        if (rms < 0.008) return
        val samples = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        var stream: OfflineStream? = null
        try {
            stream = engine.createStream()
            stream.acceptWaveform(samples, sampleRate)
            engine.decode(stream)
            val phrase = engine.getResult(stream).text.trim()
            if (phrase.isNotBlank()) {
                Log.d(TAG, "Sherpa heard: $phrase")
                handlePhrase(phrase)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sherpa decode error", e)
        } finally {
            stream?.release()
        }
    }

    private fun handlePhrase(phrase: String) {
        val normalized = phrase.lowercase(Locale.ROOT)
        val wakeWords = listOf("yoldaş", "yoldash", "yoldas", "یولداش", "یولداش")
        val wakeWord = wakeWords.firstOrNull { normalized.contains(it) }
        if (wakeWord != null) {
            val remainder = normalized.substringAfter(wakeWord).trim()
            awaitingCommand = remainder.isBlank()
            sendRecognizedPhrase(phrase)
            speak("بفرما")
        } else if (awaitingCommand) {
            sendRecognizedPhrase(phrase)
            speak("باشه")
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
        handler.post {
            if (ttsReady) tts?.speak(message, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "yoldash-sherpa")
            else Log.w(TAG, "TTS not ready; skipped speech: $message")
        }
    }

    override fun onDestroy() {
        serviceStopping = true
        active.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { listenThread?.interrupt() } catch (_: Exception) {}
        recognizer?.release()
        recognizer = null
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
        private const val TAG = "YoldashSherpa"
    }
}