package com.ping.elderlyassistant.pipeline

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Safety wrapper for the multi-step automation loop.
 *
 * - Hard timeout: [TIMEOUT_MS] — kills the loop if it hangs
 * - Step limit: [MAX_STEPS] — guards against infinite loops from LLM hallucination
 * - Settle delay: [STEP_SETTLE_MS] — waits for the screen to refresh before re-capturing
 */
object AutomationGuard {

    private const val TAG = "AutomationGuard"

    const val MAX_STEPS     = 8
    const val TIMEOUT_MS    = 30_000L
    const val STEP_SETTLE_MS = 900L

    suspend fun <T> withGuard(block: suspend () -> T): T? =
        withTimeoutOrNull(TIMEOUT_MS) { block() }
            .also { if (it == null) Log.w(TAG, "自動化逾時，已超過 ${TIMEOUT_MS / 1000}s") }
}
