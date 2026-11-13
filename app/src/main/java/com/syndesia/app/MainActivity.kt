package com.syndesia.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syndesia.app.adb.Adb
import com.syndesia.app.ui.OnboardingScreen
import com.syndesia.app.ui.SyndesiaApp
import com.syndesia.app.ui.theme.SyndesiaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Files handed to us by other apps (Open with / Share), waiting for the UI to pick them up. */
object Incoming {
    private val _uris = MutableStateFlow<List<Uri>>(emptyList())
    val uris: StateFlow<List<Uri>> = _uris.asStateFlow()
    fun offer(list: List<Uri>) { if (list.isNotEmpty()) _uris.value = _uris.value + list }
    fun take(): List<Uri> = _uris.value.also { _uris.value = emptyList() }
}

class MainActivity : ComponentActivity() {
    /** The user went somewhere else (another app, the home screen) since this was last on screen. */
    private var wentAway = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // A new launch: connect by itself again, whatever happened last time the app was open.
        if (savedInstanceState == null) Adb.appOpened(fresh = true)
        Look.init(this)
        val prefs = Prefs(this)
        setContent {
            val look by Look.state.collectAsStateWithLifecycle()
            val dark = look.isDark(isSystemInDarkTheme())
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            var onboarded by remember { mutableStateOf(prefs.onboarded) }
            SyndesiaTheme(dark, look.accent, look.background, look.pureBlack) {
                AnimatedContent(onboarded, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) }, label = "root") { done ->
                    if (done) SyndesiaApp(onReplayTour = { onboarded = false })
                    else OnboardingScreen(look) { prefs.onboarded = true; onboarded = true }
                }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Coming back to Syndesia counts as opening it: a Disconnect from the last visit no longer applies.
        if (wentAway) {
            wentAway = false
            Adb.appOpened()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) wentAway = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun handle(i: Intent?) {
        i ?: return
        val uris = when (i.action) {
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        Incoming.offer(uris)
        i.action = null
    }
}
