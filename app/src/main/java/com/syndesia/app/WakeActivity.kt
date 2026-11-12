package com.syndesia.app

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager

/**
 * Invisible screen the watch remote opens to wake the phone and bring up the PIN pad. It closes
 * itself straight away; the remote then types the PIN.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked && intent.getBooleanExtra("dismiss", true)) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
            // Stay while the PIN is typed (closing would cancel the PIN pad), but never linger.
            window.decorView.postDelayed({ if (!isFinishing) finish() }, 15000)
        } else finish()
    }
}
