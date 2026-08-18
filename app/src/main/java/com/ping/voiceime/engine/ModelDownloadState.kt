package com.ping.voiceime.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-process bridge between [ModelDownloadService] (which owns the actual download)
 * and any UI observing its progress. The download survives the UI going away —
 * this just lets a visible Activity reflect what the service is doing.
 */
object ModelDownloadState {

    data class Active(val engine: String, val progress: ModelDownloader.Progress, val startedAtMs: Long)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active

    /** Emits once per finished download: engine + success/failure. Replay=0 — late observers miss past results. */
    val results = MutableSharedFlow<Pair<String, Result<Unit>>>(extraBufferCapacity = 1)

    fun update(engine: String, progress: ModelDownloader.Progress) {
        val startedAt = _active.value?.takeIf { it.engine == engine }?.startedAtMs
            ?: System.currentTimeMillis()
        _active.value = Active(engine, progress, startedAt)
    }

    fun clear() {
        _active.value = null
    }
}
