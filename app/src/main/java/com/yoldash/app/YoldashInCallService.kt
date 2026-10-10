package com.yoldash.app

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService

class YoldashInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeCall = call
        activeService = this
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                YoldashInCallActivity.notifyCallChanged()
            }
        })
        try {
            startActivity(Intent(this, YoldashInCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        } catch (_: Exception) {}
    }

    override fun onCallRemoved(call: Call) {
        if (activeCall == call) activeCall = null
        if (activeCall == null) activeService = null
        YoldashInCallActivity.notifyCallChanged()
        super.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
    }

    override fun onAvailableCallEndpointsChanged(callEndpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(callEndpoints)
        endpoints = callEndpoints
    }

    companion object {
        @Volatile private var activeCall: Call? = null
        @Volatile private var activeService: YoldashInCallService? = null
        @Volatile private var endpoints: List<CallEndpoint> = emptyList()

        fun currentCall(): Call? = activeCall

        fun answerCall(): Boolean {
            val call = activeCall ?: return false
            return try {
                if (call.state == Call.STATE_RINGING) {
                    call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                }
                true
            } catch (_: Exception) { false }
        }

        fun endCall(): Boolean {
            val call = activeCall ?: return false
            return try { call.disconnect(); true } catch (_: Exception) { false }
        }

        fun setSpeakerphone(enabled: Boolean): Boolean {
            val service = activeService ?: return false
            if (activeCall == null) return false
            return try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val wantedType = if (enabled) CallEndpoint.TYPE_SPEAKER else CallEndpoint.TYPE_EARPIECE
                    val endpoint = endpoints.firstOrNull { it.endpointType == wantedType } ?: return false
                    service.requestCallEndpointChange(endpoint, service.mainExecutor) { _: Void? -> }
                    true
                } else {
                    val route = if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
                    service.setAudioRoute(route)
                    true
                }
            } catch (_: Exception) { false }
        }
    }
}
