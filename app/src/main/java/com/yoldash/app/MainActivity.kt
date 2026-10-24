package com.yoldash.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.os.Build
import android.telecom.TelecomManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
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
    private lateinit var permissionsButton: Button
    private var pendingCallNumber: String? = null
    private var wakeReceiverRegistered = false
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleServicePhrase(intent?.getStringExtra(YoldashWakeService.EXTRA_PHRASE).orEmpty())
        }
    }
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
        permissionsButton = Button(this).apply {
            text = "⚙ بازکردن تنظیمات اجازه‌های یولداش"
            setOnClickListener { showPermissionHelp() }
        }
        status = TextView(this).apply {
            text = "Bидارباشı aktiv edin və «Yoldaş» deyin. Sonra Azərbaycan türkcəsində danışa bilərsiniz. Proqram açıq qalmalıdır."
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }
        listOf(title, subtitle, nameInput, searchButton, voiceButton, permissionsButton, dialerRoleButton, status).forEach {
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

    override fun onResume() {
        super.onResume()
        if (!wakeReceiverRegistered) {
            val filter = IntentFilter(YoldashWakeService.ACTION_RECOGNIZED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(wakeReceiver, filter)
            }
            wakeReceiverRegistered = true
        }
    }

    override fun onPause() {
        if (wakeReceiverRegistered) {
            try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
            wakeReceiverRegistered = false
        }
        super.onPause()
    }

    private fun handleServicePhrase(phrase: String) {
        if (phrase.isBlank()) return
        val normalized = phrase.lowercase(Locale.ROOT)
        val wakeWords = listOf("یولداش", "yoldash", "yoldaş", "yoldas")
        val wakeWord = wakeWords.firstOrNull { normalized.contains(it) }
        if (wakeWord != null) {
            val wakeIndex = phrase.indexOf(wakeWord, ignoreCase = true)
            val remainder = if (wakeIndex >= 0) phrase.substring(wakeIndex + wakeWord.length).trim() else ""
            if (remainder.isNotBlank()) {
                val query = extractContactQuery(remainder)
                nameInput.setText(query)
                searchContact(query, callImmediately = false)
            } else {
                awaitingContactName = true
                status.text = "Bəli, eşidirəm. İndi kontaktın adını və əmri deyin."
            }
        } else if (awaitingContactName) {
            awaitingContactName = false
            val query = extractContactQuery(phrase)
            nameInput.setText(query)
            searchContact(query, callImmediately = true)
        }
    }

    private fun extractContactQuery(command: String): String {
        var query = command.trim().trimEnd('.', ',', '!', '?')
        val prefixes = listOf("mənə ", "mene ", "ya ", "xahiş edirəm ", "xahis edirem ",
            "zəhmət olmasa ", "zehmet olmasa ", "please ")
        prefixes.forEach { prefix ->
            if (query.lowercase(Locale.ROOT).startsWith(prefix)) {
                query = query.substring(prefix.length).trim()
            }
        }
        val suffixes = listOf(
            " mənə zəng elə", " mene zəng ele", " mənə zəng et", " mene zəng et",
            " ilə zəng elə", " ile zəng ele", " ilə zəng et", " ile zəng et",
            " ilə zəng vur", " ile zəng vur", " zəng elə", " zəng ele",
            " zəng at", " zəng et", " zəng vur", " zəng edin", " zəng eləyin",
            " zəng eleyn", " telefon et", " zənglə", " zəngle", " arayın",
            " arayin", " ara", " tut mənə", " tut mene", " zəng etsin",
            " زنگ بزن", " تماس بگیر", " تماس بزن", " call", " telefon aç", " telefon ac", " yığ", " yig"
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
        for (ending in listOf("ya", "yə", "a", "ə")) {
            if (query.lowercase(Locale.ROOT).endsWith(ending) && query.length > ending.length + 1) {
                query = query.dropLast(ending.length)
                break
            }
        }
        return query.ifBlank { command.trim() }
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

    private fun showPermissionHelp() {
        android.app.AlertDialog.Builder(this)
            .setTitle("آماده‌سازی یولداش")
            .setMessage("مدل تشخیص گفتار Sherpa باید داخل APK نصب‌شده بسته‌بندی شده باشد؛ در این حالت دانلود جداگانه لازم نیست و تشخیص آفلاین پس از نصب مدل اجرا می‌شود.\\n\\nبرای فعال‌کردن قابلیت‌ها، اجازهٔ میکروفون و مخاطبین را بدهید. اگر سامسونگ دسترسی را مسدود کرد، در صفحهٔ اطلاعات برنامه منوی ⋮ را باز کنید و «اجازهٔ تنظیمات محدودشده» را فعال کنید؛ سپس به بخش مجوزها برگردید. یولداش بدون اجازهٔ شما نمی‌تواند این دسترسی‌ها را خودش فعال کند.")
            .setPositiveButton("بازکردن تنظیمات برنامه") { _, _ -> openAppSettings() }
            .setNeutralButton("درخواست اجازه‌ها") { _, _ -> requestNeededPermissions(); requestAudioPermissionIfNeeded() }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            status.text = "تنظیمات برنامه باز نشد؛ از تنظیمات گوشی ← برنامه‌ها ← Yoldash وارد شوید."
        }
    }

    private fun requestAudioPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
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

    private fun searchContact(query: String, callImmediately: Boolean = false) {
        if (query.isBlank()) {
            status.text = "لطفاً نام مخاطب را وارد کنید."
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            status.text = "برای جست‌وجو، اجازه دسترسی به مخاطبین را فعال کنید."
            requestNeededPermissions()
            return
        }
        val allContacts = mutableListOf<Pair<String, String>>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue
                    if (allContacts.none { it.first == name && it.second == number }) {
                        allContacts.add(name to number)
                    }
                }
            }
        } catch (_: SecurityException) {
            status.text = "دسترسی به مخاطبین مجاز نیست."
            return
        }

        fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace('ə', 'e').replace('ı', 'i').replace('ö', 'o')
            .replace('ü', 'u').replace('ş', 's').replace('ç', 'c')
            .replace('ğ', 'g')
            .filter { it.isLetterOrDigit() }

        val wanted = normalize(query)
        val ranked = allContacts.map { contact ->
            val normalizedName = normalize(contact.first)
            val score = when {
                normalizedName == wanted -> 100
                normalizedName.startsWith(wanted) -> 80
                normalizedName.split(Regex("\\s+")).any { it == wanted } -> 75
                normalizedName.contains(wanted) -> 60
                wanted.length >= 3 && normalizedName.split(Regex("\\s+")).any {
                    it.startsWith(wanted) || wanted.startsWith(it)
                } -> 40
                else -> 0
            }
            Triple(contact, score, normalizedName)
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<Pair<String, String>, Int, String>> { it.second }
                .thenBy { it.first.first.length })

        if (ranked.isEmpty()) {
            status.text = "نام «$query» با اطمینان در مخاطبین پیدا نشد. نام را تایپ کنید یا دوباره واضح بگویید."
            return
        }
        val bestScore = ranked.first().second
        val best = ranked.filter { it.second == bestScore }
            .map { it.first }
            .distinctBy { it.second }
            .take(8)

        if (best.size == 1 && bestScore >= 80) {
            showContact(best.first().first, best.first().second, callImmediately)
        } else {
            val labels = best.map { "${it.first} — ${it.second}" }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("لطفاً مخاطب را تأیید کن")
                .setItems(labels) { _, which -> showContact(best[which].first, best[which].second, callImmediately) }
                .setNegativeButton("لغو", null)
                .show()
        }
    }

    private fun showContact(name: String, number: String, callImmediately: Boolean = false) {
        status.text = "مخاطب: $name\nشماره: $number"
        if (callImmediately) {
            directCall(number)
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("تأیید تماس")
            .setMessage("با $name به شماره $number تماس گرفته شود؟")
            .setPositiveButton("بله، باز کردن شماره‌گیر") { _, _ -> dial(number) }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun directCall(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallNumber = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 46)
            status.text = "شماره: $number\nبرای تماس، اجازهٔ تماس را تأیید کنید."
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
        } catch (_: Exception) {
            status.text = "شماره پیدا شد: $number\nتماس شروع نشد؛ اجازهٔ تماس را بررسی کنید."
        }
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
        } else if (requestCode == 46) {
            val number = pendingCallNumber
            pendingCallNumber = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && number != null) {
                directCall(number)
            } else {
                status.text = "شماره پیدا شد، اما برای تماس مستقیم اجازهٔ تماس لازم است."
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
