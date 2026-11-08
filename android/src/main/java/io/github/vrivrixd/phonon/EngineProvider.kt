package io.github.vrivrixd.phonon

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * How the settings screen talks to the engine, which runs in its own process (":engine"): it asks
 * for the model's state and can restart the engine without taking the screen down with it.
 */
class EngineProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        STATUS -> Bundle().apply {
            when (val s = Model.state) {
                is Model.State.Idle, is Model.State.Loading -> putString(STATE, LOADING)
                is Model.State.Extracting -> {
                    putString(STATE, EXTRACTING)
                    putString(DETAIL, s.file)
                }
                is Model.State.Failed -> {
                    putString(STATE, FAILED)
                    putString(DETAIL, s.message)
                }
                is Model.State.Ready -> {
                    putString(STATE, READY)
                    putDouble(SECONDS, s.seconds)
                    putBoolean(OPTIMIZED, PhononTTS.variant == "ptts_ffi")
                }
            }
        }
        RESTART -> {
            // A moment later, so this call returns first. The system starts the engine again for
            // whichever apps are still bound to it.
            Thread {
                Thread.sleep(200)
                Process.killProcess(Process.myPid())
            }.start()
            null
        }
        else -> null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val STATUS = "status"
        const val RESTART = "restart"
        const val STATE = "state"
        const val DETAIL = "detail"
        const val SECONDS = "seconds"
        const val OPTIMIZED = "optimized"
        const val LOADING = "loading"
        const val EXTRACTING = "extracting"
        const val FAILED = "failed"
        const val READY = "ready"

        /**
         * Call the engine's process, starting it if needed; null if that fails. The client is
         * unstable on purpose: a stable one would make Android kill this process with the engine.
         */
        fun call(context: Context, method: String): Bundle? = try {
            context.contentResolver.acquireUnstableContentProviderClient("${context.packageName}.engine")?.let { client ->
                try {
                    client.call(method, null, null)
                } finally {
                    client.close()
                }
            }
        } catch (e: Exception) {
            Log.w("PhononEngine", "engine call failed: $method", e)
            null
        }
    }
}
