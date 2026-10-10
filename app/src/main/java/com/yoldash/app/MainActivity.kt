package com.yoldash.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.os.Build
import android.telecom.TelecomManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
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
    private lateinit var dialerRoleButton: Button
    private var pendingCallNumber: String? = null
    private var wakeMode = false
    private var awaitingContactName = false
    private val contactsPermissionRequest = 42
    private val audioPermissionRequest = 43

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleDialIntent(intent)
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
            setSingleLine(true)
        }
        searchButton = Button(this).apply {
            text = "جست‌وجوی مخاطب"
            setOnClickListener { searchContact(nameInput.text.toString().trim()) }
        }
        voiceButton = Button(this).apply {
            text = "🎙 فعال‌کردن بیدارباش «یولداش»"
            setOnClickListener { if (wakeMode) stopWakeListening() else startWakeListening() }
        }
        dialerRoleButton = Button(this).apply {
            text = "تنظیم یولداش به‌عنوان تلفن پیش‌فرض"
            setOnClickListener { requestDefaultDialerRole() }
        }
        status = TextView(this).apply {
            text = "Bидارباشı aktiv edin və «Yoldaş» deyin. Sonra Azərbaycan türkcəsində danışa bilərsiniz. Proqram açıq qalmalıdır."
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }
        listOf(title, subtitle, nameInput, searchButton, voiceButton, dialerRoleButton, status).forEach {
            root.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 })
        }
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    private fun handleDialIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_DIAL) {
            val number = intent.data?.schemeSpecificPart?.trim().orEmpty()
            if (number.isNotBlank()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("تأیید تماس")
                    .setMessage("با شماره $number تماس گرفته شود؟")
                    .setPositiveButton("تماس") { _, _ -> dial(number) }
                    .setNegativeButton("لغو", null)
                    .show()
            }
        }
    }

    private fun requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER), 44)
            } else if (roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                status.text = "یولداش از قبل برنامه تلفن پیش‌فرض است."
            } else {
                status.text = "نقش تلفن پیش‌فرض در این گوشی در دسترس نیست."
            }
        } else {
            status.text = "برای تنظیم تلفن پیش‌فرض، نسخه اندروید این گوشی نیاز به بررسی دارد."
        }
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
        try {
            val serviceIntent = Intent(this, YoldashWakeService::class.java).apply {
                action = YoldashWakeService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            wakeMode = true
            awaitingContactName = false
            voiceButton.text = "⏹ خاموش‌کردن بیدارباش"
            status.text = "بیدارباش پس‌زمینه فعال شد. حتی با قفل صفحه، «یولداش» را بگویید."
        } catch (_: Exception) {
            wakeMode = false
            status.text = "شروع بیدارباش ممکن نشد. مجوز میکروفون و تنظیمات باتری را بررسی کنید."
        }
    }

    private fun handleWakeSpeech(spoken: String) {
        if (spoken.isBlank()) {
            scheduleNextListening(500)
            return
        }
        val normalized = spoken.lowercase(Locale.ROOT)
        val normalizedAscii = normalized.replace('ə', 'e')
        if (normalizedAscii.contains("dinamiki ac") || normalized.contains("dinamiki aç") ||
            normalizedAscii.contains("sesguclendiriciye ver") || normalizedAscii.contains("speaker on") ||
            normalizedAscii.contains("loudspeaker on")) {
            val ok = YoldashInCallService.setSpeakerphone(true)
            status.text = if (ok) "بلندگوی تماس روشن شد." else
                "تماس فعالی برای کنترل بلندگو پیدا نشد؛ هنگام تماس دوباره فرمان بدهید."
            scheduleNextListening(700)
            return
        }
        if (normalizedAscii.contains("dinamiki bagla") || normalized.contains("dinamiki bağla") ||
            normalizedAscii.contains("speaker off") || normalizedAscii.contains("loudspeaker off") ||
            normalizedAscii.contains("qulaqliga kec")) {
            val ok = YoldashInCallService.setSpeakerphone(false)
            status.text = if (ok) "بلندگوی تماس خاموش شد." else
                "تماس فعالی برای کنترل مسیر صدا پیدا نشد."
            scheduleNextListening(700)
            return
        }
        val wakeWords = listOf("یولداش", "yoldash", "yoldaş", "yoldas")
        if (!awaitingContactName) {
            val wakeWord = wakeWords.firstOrNull { normalized.contains(it) }
            if (wakeWord != null) {
                val remainder = spoken.substringAfter(wakeWord, "").trim()
                if (remainder.isNotBlank()) {
                    val query = extractContactQuery(remainder)
                    stopWakeListening()
                    nameInput.setText(query)
                    searchContact(query)
                } else {
                    awaitingContactName = true
                    status.text = "Bəli, eşidirəm. İndi deyin: məsələn, «Əhmədə zəng elə»."
                    scheduleNextListening(400)
                }
            } else {
                scheduleNextListening(400)
            }
        } else {
            val query = extractContactQuery(spoken)
            stopWakeListening()
            nameInput.setText(query)
            searchContact(query)
        }
    }

    private fun extractContactQuery(command: String): String {
        var query = command.trim().trimEnd('.', ',', '!', '?')
        val prefixes = listOf(
            "mənə ", "mene ", "ya ", "xahiş edirəm ", "xahis edirem ",
            "zəhmət olmasa ", "zehmet olmasa ", "please "
        )
        prefixes.forEach { prefix ->
            if (query.lowercase(Locale.ROOT).startsWith(prefix)) {
                query = query.substring(prefix.length).trim()
            }
        }
        val suffixes = listOf(
            " zəng elə", " zəng ele", " zəng at", " zəng et", " zəng vur",
            " zəng edin", " telefon et", " zənglə", " zəngle",
            " ara", " arayın", " arayin", " tut mənə", " tut mene", " zəng etsin",
            " call", " telefon aç", " telefon ac"
        )
        var changed = true
        while (changed) {
            changed = false
            suffixes.forEach { suffix ->
                if (query.lowercase(Locale.ROOT).endsWith(suffix)) {
                    query = query.dropLast(suffix.length).trim()
                    changed = true
                }
            }
        }
        val caseEndings = listOf("ya", "yə", "a", "ə")
        for (ending in caseEndings) {
            if (query.lowercase(Locale.ROOT).endsWith(ending) && query.length > ending.length + 1) {
                query = query.dropLast(ending.length)
                break
            }
        }
        return query.ifBlank { command.trim() }
    }

    private fun stopWakeListening() {
        wakeMode = false
        awaitingContactName = false
        try { stopService(Intent(this, YoldashWakeService::class.java)) } catch (_: Exception) {}
        if (::voiceButton.isInitialized) voiceButton.text = "🎙 فعال‌کردن بیدارباش «یولداش»"
        if (::status.isInitialized) status.text = "بیدارباش خاموش است. برای شروع دوباره، دکمه میکروفون را بزنید."
    }

    override fun onDestroy() {
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
            val roleManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getSystemService(RoleManager::class.java) else null
            val isDefaultDialer = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
            if (isDefaultDialer) {
                if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                    pendingCallNumber = number
                    requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 45)
                    return
                }
                getSystemService(TelecomManager::class.java)
                    .placeCall(Uri.parse("tel:" + Uri.encode(number)), Bundle())
            } else {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
            }
        } catch (_: Exception) {
            Toast.makeText(this, "شروع تماس ممکن نشد؛ شماره را در شماره‌گیر بررسی کنید.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startWakeListening()
        } else if (requestCode == contactsPermissionRequest && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            status.text = "برای پیدا کردن مخاطب، اجازه مخاطبین لازم است."
        } else if (requestCode == 45) {
            val number = pendingCallNumber
            pendingCallNumber = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && number != null) {
                dial(number)
            } else {
                status.text = "برای شروع تماس از داخل یولداش، اجازه تماس لازم است."
            }
        } else if (requestCode == 44) {
            val roleManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getSystemService(RoleManager::class.java) else null
            status.text = if (roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true)
                "یولداش تلفن پیش‌فرض شد. اکنون تماس‌ها از طریق سرویس تماس یولداش قابل مدیریت‌اند."
            else "درخواست تلفن پیش‌فرض پذیرفته نشد؛ برنامه فعلی بدون این نقش کار می‌کند."
        }
    }
}
