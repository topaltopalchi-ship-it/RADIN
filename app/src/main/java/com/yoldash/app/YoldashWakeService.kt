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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
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
    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var listenThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    val result = engine.setLanguage(Locale("fa", "IR"))
                    if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                        result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Persian TTS unavailable.")
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
            listenThread = thread(name = "YoldashVoskMic") { runRecognition() }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Yoldash voice assistant", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Offline Vosk voice recognition."
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
            .setContentText("تشخیص گفتار فارسی آفلاین با Vosk؛ بگویید «یولداش».")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp).setOngoing(true).build()
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            if (!destination.exists()) destination.mkdirs()
            for (child in children) copyAssetTree("$assetPath/$child", File(destination, child))
        } else {
            if (destination.exists() && destination.length() > 0L) return
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        }
    }

    private fun runRecognition() {
        var localRecorder: AudioRecord? = null
        try {
            val modelDir = File(filesDir, "vosk-model-small-fa-0.42")
            if (!File(modelDir, "am").exists()) {
                copyAssetTree("vosk-model-small-fa-0.42", modelDir)
            }
            voskModel = Model(modelDir.absolutePath)
            recognizer = Recognizer(voskModel, 16000.0f)

            val sampleRate = 16000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("AudioRecord buffer unavailable: $minBuffer")
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, sampleRate * 2)
            )
            localRecorder = recorder
            audioRecord = recorder
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone initialization failed")
            recorder.startRecording()
            speak("یولداش فعال شد.")
            val input = ShortArray(2048)
            val bytes = ByteArray(input.size * 2)
            while (active.get() && !Thread.currentThread().isInterrupted) {
                val count = recorder.read(input, 0, input.size)
                if (count <= 0) {
                    Log.w(TAG, "AudioRecord read returned $count")
                    continue
                }
                for (i in 0 until count) {
                    val sample = input[i].toInt()
                    bytes[i * 2] = (sample and 0xff).toByte()
                    bytes[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                }
                val engine = recognizer ?: continue
                if (engine.acceptWaveForm(bytes, count * 2)) {
                    val phrase = JSONObject(engine.result).optString("text").trim()
                    if (phrase.isNotBlank()) {
                        Log.d(TAG, "Vosk heard: $phrase")
                        handlePhrase(phrase)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk offline recognition failed", e)
            handler.post { speak("تشخیص گفتار راه‌اندازی نشد؛ مجوز میکروفون را بررسی کنید.") }
        } finally {
            try { localRecorder?.stop() } catch (_: Exception) {}
            try { localRecorder?.release() } catch (_: Exception) {}
            audioRecord = null
            try { recognizer?.close() } catch (_: Exception) {}
            recognizer = null
            try { voskModel?.close() } catch (_: Exception) {}
            voskModel = null
            active.set(false)
            if (!serviceStopping) {
                handler.postDelayed({
                    if (!serviceStopping && !active.get() &&
                        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "Restarting Vosk recognition after loop exit")
                        if (active.compareAndSet(false, true)) {
                            listenThread = thread(name = "YoldashVoskMic") { runRecognition() }
                        }
                    }
                }, 1500L)
            }
        }
    }

    private fun handlePhrase(phrase: String) {
        val normalized = phrase.lowercase(Locale.ROOT)
        val wakeWords = listOf("یولداش", "yoldash", "yoldaş", "yoldas")
        val wakeWord = wakeWords.firstOrNull { normalized.contains(it) }
        if (wakeWord != null) {
            val remainder = normalized.substringAfter(wakeWord).trim()
            awaitingCommand = remainder.isBlank()
            sendRecognizedPhrase(phrase)
            speak("Buyur")
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
            if (ttsReady) tts?.speak(message, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "yoldash-vosk")
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
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { voskModel?.close() } catch (_: Exception) {}
        voskModel = null
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
        private const val TAG = "YoldashVosk"
    }
}