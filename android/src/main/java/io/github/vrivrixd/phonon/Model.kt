package io.github.vrivrixd.phonon

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The bundled model: copied out of the APK's assets on first run (the runtime loads a folder),
 * then loaded once, off the main thread, and kept for the life of the process.
 */
object Model {
    private const val TAG = "PhononModel"
    private const val ASSET_DIR = "model"
    private const val STAMP = "PACK_VERSION"

    sealed class State {
        object Idle : State()
        class Extracting(val file: String) : State()
        object Loading : State()
        class Ready(val tts: PhononTTS, val seconds: Double) : State()
        class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    private val loader = Executors.newSingleThreadExecutor { Thread(it, "Phonon loader") }
    private var pending: Future<*>? = null

    fun dir(context: Context) = File(context.filesDir, "model")

    /** Start extracting and loading, unless that is done or under way. */
    @Synchronized
    fun load(context: Context): Future<*>? {
        if (state is State.Ready || pending?.isDone == false) return pending
        val app = context.applicationContext
        state = State.Loading
        pending = loader.submit {
            val start = System.nanoTime()
            state = try {
                extract(app)
                state = State.Loading
                // Read once by the runtime's thread pool, before the first load.
                Os.setenv("RAYON_NUM_THREADS", Prefs.threads(app).toString(), true)
                val tts = PhononTTS(dir(app).path, "pt")
                State.Ready(tts, (System.nanoTime() - start) / 1e9)
            } catch (t: Throwable) {
                Log.e(TAG, "loading the model failed", t)
                State.Failed(t.message ?: t.toString())
            }
        }
        return pending
    }

    /** The loaded model, waiting up to [timeoutS] for a load in progress. */
    fun await(context: Context, timeoutS: Long = 90): PhononTTS? {
        try {
            load(context)?.get(timeoutS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "waiting for the model failed", e)
        }
        return (state as? State.Ready)?.tts
    }

    /** Copy the bundled model out of the APK, unless this version is already there. */
    private fun extract(context: Context) {
        val assets = context.assets
        val wanted = assets.open("$ASSET_DIR/$STAMP").use { it.readBytes().toString(Charsets.UTF_8) }.trim()
        val target = dir(context)
        val stamp = File(target, STAMP)
        if (stamp.isFile && stamp.readText().trim() == wanted) return

        val staging = File(context.filesDir, "model.staging")
        staging.deleteRecursively()
        fun copyTree(assetPath: String, into: File) {
            val children = assets.list(assetPath).orEmpty()
            if (children.isEmpty()) {
                state = State.Extracting(into.name)
                into.parentFile?.mkdirs()
                assets.open(assetPath).use { input -> into.outputStream().use { input.copyTo(it, 1 shl 20) } }
                return
            }
            for (child in children) copyTree("$assetPath/$child", File(into, child))
        }
        copyTree(ASSET_DIR, staging)
        target.deleteRecursively()
        check(staging.renameTo(target)) { "could not move the model into place" }
    }

}
