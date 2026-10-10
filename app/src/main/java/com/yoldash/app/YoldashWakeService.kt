package com.yoldash.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class YoldashWakeService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var awaitingCommand = false
    @Volatile private var active = true

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        active = true
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!listening) beginRecognition()
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val azeri = engine.setLanguage(Locale("az", "AZ"))
            if (azeri == TextToSpeech.LANG_MISSING_DATA ||
                azeri == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale("tr", "TR"))
            }
            ttsReady = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Yoldash voice assistant", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps Yoldash voice wake-up active while the screen is locked."
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
            .setContentText("برای بیدارکردن یولداش بگویید «یولداش»؛ لمس کنید تا برنامه باز شود.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp).setOngoing(true).build()
    }

    private fun beginRecognition() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("تشخیص گفتار در این گوشی در دسترس نیست.")
            stopSelf()
            return
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) { scheduleListening(900) }
                        override fun onResults(results: Bundle?) {
                            val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                            handlePhrase(phrase)
                            scheduleListening(150)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "az-AZ")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "az-AZ")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            recognizer?.startListening(speechIntent)
            listening = true
        } catch (_: Exception) {
            listening = false
            scheduleListening(1500)
        }
    }

    private fun handlePhrase(phrase: String) {
        if (phrase.isBlank()) return
        val normalized = phrase.lowercase(Locale.ROOT)
        val wake = listOf("yoldaş", "yoldash", "yoldas", "یولداش").any { normalized.contains(it) }
        if (wake) {
            val wakeWord = listOf("yoldaş", "yoldash", "yoldas", "یولداش")
                .firstOrNull { normalized.contains(it) }
            val remainder = wakeWord?.let { normalized.substringAfter(it).trim() }.orEmpty()
            // If the user says the wake word and the command in one sentence,
            // do not leave the service waiting for an extra command.
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

    private fun scheduleListening(delayMs: Long) {
        listening = false
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (active) beginRecognition() }, delayMs)
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
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
    }
}
