package com.syndesia.app.adb

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen recording with the watch's own `screenrecord`: records to a temp file on the watch,
 * stops with SIGINT (which finishes the MP4 cleanly), then saves it to Downloads/Syndesia/Recordings.
 */
object Recorder {
    sealed interface State {
        data object Idle : State
        data class Recording(val startedAt: Long) : State
        data object Stopping : State
        data class Saving(val name: String) : State
        data class Saved(val name: String) : State
        data class Error(val message: String) : State
    }

    /** screenrecord's own cap. */
    const val MAX_SECONDS = 180

    private const val TMP = "/data/local/tmp/syndesia_rec.mp4"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun start(context: Context) {
        if (_state.value is State.Recording || _state.value is State.Stopping || _state.value is State.Saving) return
        val app = context.applicationContext
        val startedAt = System.currentTimeMillis()
        _state.value = State.Recording(startedAt)
        job = scope.launch {
            runCatching { Adb.sh("rm -f $TMP") }
            // Blocks until the recording ends (Stop, or the time limit).
            val out = runCatching { Adb.sh("screenrecord --bit-rate 4000000 --time-limit $MAX_SECONDS $TMP") }
                .getOrElse { e -> _state.value = State.Error(e.message ?: "Lost the watch while recording"); return@launch }
            delay(400)
            val file = runCatching { Sync.use { it.stat(TMP) } }.getOrNull()
            if (file == null || file.mode == 0 || file.size == 0L) {
                val why = out.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
                _state.value = State.Error(
                    if (why.contains("not found", true)) "This watch doesn't have a screen recorder."
                    else if (System.currentTimeMillis() - startedAt < 1500 && why.isNotBlank()) "The watch couldn't record: $why"
                    else "The watch didn't save a recording. Wake its screen and try again."
                )
                return@launch
            }
            val name = "watch_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt)) + ".mp4"
            _state.value = State.Saving(name)
            Transfers.download(app, file.copy(name = name), subfolder = "Recordings", rename = name) { ok ->
                runCatching { Adb.sh("rm -f $TMP") }
                _state.value = if (ok) State.Saved(name) else State.Error("Couldn't save the recording to the phone")
            }
        }
    }

    /** Stop recording; screenrecord finalises the file on SIGINT. */
    fun stop() {
        if (_state.value !is State.Recording) return
        _state.value = State.Stopping
        scope.launch {
            runCatching { Adb.sh("pkill -INT screenrecord || kill -INT \$(pidof screenrecord)") }
        }
    }

    fun dismiss() {
        val s = _state.value
        if (s is State.Saved || s is State.Error) _state.value = State.Idle
    }
}
