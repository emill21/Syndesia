package com.syndesia.wear

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Bluetooth messages from Syndesia on the phone. */
class WakeListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_WAKE -> WifiKeeper.start(this)
            PATH_RELEASE -> WifiHold.release(this)
            Phone.PATH_REPLY -> Phone.onReply(event.data)
            PATH_PING -> runCatching {
                val id = org.json.JSONObject(String(event.data, Charsets.UTF_8)).getString("id")
                val v = packageManager.getPackageInfo(packageName, 0).longVersionCode
                Receiver.result(this, event.sourceNodeId, id, "pong", v.toString())
            }
            PATH_HOTSPOT -> runCatching { org.json.JSONObject(String(event.data, Charsets.UTF_8)) }
                .getOrNull()?.let { HotspotJoin.join(this, event.sourceNodeId, it) }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path == Receiver.PATH_CHANNEL) Receiver.accept(this, channel)
    }

    companion object {
        const val PATH_WAKE = "/syndesia/wake"
        const val PATH_RELEASE = "/syndesia/release"
        const val PATH_HOTSPOT = "/syndesia/hotspot"
        const val PATH_PING = "/syndesia/ping"
    }
}
