package com.yoldash.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
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
    private var pendingNumber: String? = null
    private val voiceRequest = 41
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
            text = "🎙 گفتن نام مخاطب"
            setOnClickListener { startVoiceSearch() }
        }
        status = TextView(this).apply {
            text = "برای شروع، نام مخاطب را بنویسید یا دکمه میکروفون را بزنید."
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

    private fun startVoiceSearch() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "نام مخاطب را بگویید")
        }
        try {
            startActivityForResult(intent, voiceRequest)
        } catch (_: Exception) {
            status.text = "تشخیص گفتار روی این گوشی در دسترس نیست؛ نام را تایپ کنید."
        }
    }

    @Deprecated("Uses the standard Android speech-recognition activity for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == voiceRequest && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            nameInput.setText(spoken)
            searchContact(spoken.trim())
        }
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
        pendingNumber = number
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
            startVoiceSearch()
        } else if (requestCode == contactsPermissionRequest && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            status.text = "برای پیدا کردن مخاطب، اجازه مخاطبین لازم است."
        }
    }
}
