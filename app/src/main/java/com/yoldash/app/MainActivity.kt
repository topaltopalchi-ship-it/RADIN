package com.yoldash.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var nameInput: EditText
    private lateinit var status: TextView
    private lateinit var searchButton: Button
    private lateinit var voiceButton: Button
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeMode = false
    private var awaitingContactName = false
    private val contactsPermissionRequest = 42
    private val audioPermissionRequest = 43

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNeededPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 48, 28, 24)
            setBackgroundColor(0xFFF5F7F6.toInt())
        }
        val title = TextView(this).apply {
            text = "Yoldash"
            textSize = 34f
            setTextColor(0xFF173D32.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "منشی هوشمند شما"
            textSize = 19f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 28)
        }
        nameInput = EditText(this).apply {
            hint = "نام مخاطب را بنویسید"
            textSize = 18f
            singleLine = true
        }
        searchButton = Button(this).apply {
            text = "جست‌وجوی مخاطب"
            setOnClickListener { searchContact(nameInput.text.toString().trim()) }
        }
        voiceButton = Button(this).apply {
            text = "🎙 فعال‌کردن بیدارباش «یولداش»"
            setOnClickListener { if (wakeMode) stopWakeListening() else startWakeListening() }
        }
        status = TextView(this).apply {
            text = "نام مخاطب را تایپ کنید، یا بیدارباش را فعال کنید و بگویید «یولداش». برای شنیدن پیوسته، برنامه باید باز بماند."
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }
        listOf(title, subtitle, nameInput, searchButton, voiceButton, status).forEach {
            root.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 })
        }
        setContentView(root)
    }

    private fun requestNeededPermissions() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), contactsPermissionRequest)
        }
    }

    private fun startWakeListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "تشخیص گفتار در این گوشی در دسترس نیست؛ می‌توانید نام را تایپ کنید."
            return
        }
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            if (wakeMode) status.text = if (awaitingContactName)
                                "یولداش گوش می‌دهد؛ حالا نام مخاطب را بگویید."
                            else "یولداش گوش می‌دهد؛ نام «یولداش» را بگویید."
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            if (wakeMode) scheduleNextListening(700)
                        }
                        override fun onResults(results: Bundle?) {
                            if (!wakeMode) return
                            val spoken = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()?.trim().orEmpty()
                            handleWakeSpeech(spoken)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
            wakeMode = true
            awaitingContactName = false
            voiceButton.text = "⏹ خاموش‌کردن بیدارباش"
            status.text = "برای شروع، بگویید «یولداش». برنامه باید باز بماند."
            startRecognizerListening()
        } catch (_: Exception) {
            stopWakeListening()
            status.text = "فعال‌کردن میکروفون ممکن نشد؛ نام را تایپ کنید."
        }
    }

    private fun startRecognizerListening() {
        if (!wakeMode) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            scheduleNextListening(1000)
        }
    }

    private fun scheduleNextListening(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (wakeMode) startRecognizerListening()
        }, delayMs)
    }

    private fun handleWakeSpeech(spoken: String) {
        if (spoken.isBlank()) {
            scheduleNextListening(500)
            return
        }
        val normalized = spoken.lowercase(Locale.ROOT)
        val wakeWords = listOf("یولداش", "یولداش", "yoldash", "yoldaş", "yoldas")
        if (!awaitingContactName) {
            val wakeWord = wakeWords.firstOrNull { normalized.contains(it) }
            if (wakeWord != null) {
                val remainder = spoken.substringAfter(wakeWord, "").trim()
                    .removePrefix("به ").removeSuffix(" زنگ بزن").removeSuffix(" تماس بگیر").trim()
                if (remainder.isNotBlank()) {
                    stopWakeListening()
                    nameInput.setText(remainder)
                    searchContact(remainder)
                } else {
                    awaitingContactName = true
                    status.text = "بله، گوش می‌دهم؛ حالا نام مخاطب را بگویید."
                    scheduleNextListening(500)
                }
            } else {
                scheduleNextListening(400)
            }
        } else {
            stopWakeListening()
            nameInput.setText(spoken)
            searchContact(spoken)
        }
    }

    private fun stopWakeListening() {
        wakeMode = false
        awaitingContactName = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        if (::voiceButton.isInitialized) voiceButton.text = "🎙 فعال‌کردن بیدارباش «یولداش»"
        if (::status.isInitialized) status.text = "بیدارباش خاموش است. برای شروع دوباره، دکمه میکروفون را بزنید."
    }

    override fun onDestroy() {
        stopWakeListening()
        super.onDestroy()
    }

    private fun searchContact(query: String) {
        if (query.isBlank()) {
            status.text = "لطفاً نام مخاطب را وارد کنید."
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            status.text = "برای جست‌وجو، اجازه دسترسی به مخاطبین را فعال کنید."
            requestNeededPermissions()
            return
        }
        val matches = mutableListOf<Pair<String, String>>()
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                while (it.moveToNext() && matches.size < 8) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    if (matches.none { entry -> entry.second == number }) matches.add(name to number)
                }
            }
        } catch (_: SecurityException) {
            status.text = "دسترسی به مخاطبین مجاز نیست."
            return
        }
        if (matches.isEmpty()) {
            status.text = "مخاطبی با نام «$query» پیدا نشد."
            return
        }
        if (matches.size == 1) {
            showContact(matches.first().first, matches.first().second)
        } else {
            val labels = matches.map { "${it.first} — ${it.second}" }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("کدام مخاطب؟")
                .setItems(labels) { _, which -> showContact(matches[which].first, matches[which].second) }
                .setNegativeButton("لغو", null)
                .show()
        }
    }

    private fun showContact(name: String, number: String) {
        status.text = "مخاطب: $name\nشماره: $number"
        android.app.AlertDialog.Builder(this)
            .setTitle("تأیید تماس")
            .setMessage("با $name به شماره $number تماس گرفته شود؟")
            .setPositiveButton("بله، باز کردن شماره‌گیر") { _, _ -> dial(number) }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun dial(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        } catch (_: Exception) {
            Toast.makeText(this, "باز کردن شماره‌گیر ممکن نشد.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startWakeListening()
        } else if (requestCode == contactsPermissionRequest && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            status.text = "برای پیدا کردن مخاطب، اجازه مخاطبین لازم است."
        }
    }
}
