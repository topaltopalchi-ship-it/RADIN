package com.yoldash.app

import android.app.Activity
import android.os.Bundle
import android.telecom.Call
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class YoldashInCallActivity : Activity() {
    private lateinit var callInfo: TextView
    private lateinit var answerButton: Button
    private lateinit var speakerButton: Button
    private var speakerEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_CALL_ENDED, false)) {
            Toast.makeText(this, "تماس پایان یافت", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        buildUi()
        refreshCallInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshCallInfo()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 40, 28, 32)
        }
        callInfo = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 28)
        }
        answerButton = Button(this).apply {
            text = "پاسخ به تماس"
            setOnClickListener {
                if (!YoldashInCallService.answerCall()) {
                    Toast.makeText(this@YoldashInCallActivity, "تماس ورودی در دسترس نیست", Toast.LENGTH_SHORT).show()
                }
                refreshCallInfo()
            }
        }
        speakerButton = Button(this).apply {
            text = "فعال‌کردن بلندگو"
            setOnClickListener {
                val next = !speakerEnabled
                if (YoldashInCallService.setSpeakerphone(next)) {
                    speakerEnabled = next
                    text = if (speakerEnabled) "خاموش‌کردن بلندگو" else "فعال‌کردن بلندگو"
                } else {
                    Toast.makeText(this@YoldashInCallActivity, "تغییر مسیر صدا ممکن نشد", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val endButton = Button(this).apply {
            text = "قطع / رد تماس"
            setOnClickListener {
                YoldashInCallService.endCall()
                finish()
            }
        }
        listOf(callInfo, answerButton, speakerButton, endButton).forEach {
            root.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 })
        }
        setContentView(root)
    }

    private fun refreshCallInfo() {
        val call = YoldashInCallService.currentCall()
        if (call == null) {
            callInfo.text = "تماسی فعال نیست"
            answerButton.isEnabled = false
            speakerButton.isEnabled = false
            return
        }
        val handle = call.details.handle?.schemeSpecificPart.orEmpty()
        callInfo.text = when (call.state) {
            Call.STATE_RINGING -> "تماس ورودی\n$handle"
            Call.STATE_DIALING -> "در حال تماس با\n$handle"
            Call.STATE_ACTIVE -> "تماس برقرار است\n$handle"
            Call.STATE_HOLDING -> "تماس در انتظار\n$handle"
            Call.STATE_DISCONNECTED -> "تماس پایان یافته"
            else -> "وضعیت تماس\n$handle"
        }
        answerButton.isEnabled = call.state == Call.STATE_RINGING
        speakerButton.isEnabled = call.state == Call.STATE_ACTIVE
    }

    companion object {
        const val EXTRA_CALL_ENDED = "com.yoldash.app.CALL_ENDED"
        @Volatile private var currentActivity: YoldashInCallActivity? = null
        fun notifyCallChanged() {
            currentActivity?.runOnUiThread { currentActivity?.refreshCallInfo() }
        }
    }

    override fun onStart() {
        super.onStart()
        currentActivity = this
    }

    override fun onStop() {
        if (currentActivity === this) currentActivity = null
        super.onStop()
    }
}
