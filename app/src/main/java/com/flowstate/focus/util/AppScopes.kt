package com.flowstate.focus.util

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * 1.8.7: process-wide coroutine scopes for singletons, services and receivers.
 *
 * A plain `CoroutineScope(SupervisorJob() + dispatcher)` hands any exception a launched coroutine does
 * not catch to the thread's default handler, which kills the whole app. These background jobs (cloud
 * sync, presence, widgets, session inserts) are best-effort: a failure is logged and the job ends, the
 * app keeps running.
 */
object AppScopes {
    fun background(dispatcher: CoroutineDispatcher, tag: String): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher + handler(tag))

    fun handler(tag: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e("Flowstate", "Uncaught error in $tag", e)
    }
}
