package io.github.vrivrixd.phonon

import android.os.Process
import android.util.Log
import java.io.File

/**
 * Keeps speech on time when the processor is busy. Android's own playback thread comes first, since
 * it must never wait for the model. The model's workers are native threads made by the runtime, so
 * they are found in /proc.
 */
class EngineThreads {
    private val seen = HashSet<Int>()

    /** Raise any thread not seen yet. Cheap to repeat: known threads are skipped. */
    fun boost() {
        val tasks = File("/proc/self/task").list() ?: return
        for (task in tasks) {
            val tid = task.toIntOrNull() ?: continue
            if (!seen.add(tid)) continue
            val name = try {
                File("/proc/self/task/$task/comm").readText().trim()
            } catch (e: Exception) {
                continue
            }
            if (name.startsWith("Binder:")) continue
            val priority = if (name.startsWith("TTS.Audio")) Process.THREAD_PRIORITY_URGENT_AUDIO else Process.THREAD_PRIORITY_VIDEO
            try {
                Process.setThreadPriority(tid, priority)
            } catch (e: Exception) {
                Log.d("PhononThreads", "could not raise thread $name", e)
            }
        }
    }
}
