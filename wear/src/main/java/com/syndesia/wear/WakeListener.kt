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
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path == Receiver.PATH_CHANNEL) Receiver.accept(this, channel)
    }

    companion object {
        const val PATH_WAKE = "/syndesia/wake"
        const val PATH_RELEASE = "/syndesia/release"
    }
}
