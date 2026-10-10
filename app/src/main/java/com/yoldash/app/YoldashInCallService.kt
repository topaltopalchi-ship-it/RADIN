package com.yoldash.app

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
    }

    override fun onCallRemoved(call: Call) {
        if (activeCall == call) activeCall = null
        if (activeCall == null) activeService = null
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
            } catch (_: Exception) {
                false
            }
        }
    }
}
