package io.github.vrivrixd.phonon

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a new version on GitHub when the app opens and, if the person agrees, downloads it and
 * hands it to the system installer. A lookup that fails stays silent: no network is no reason to
 * bother anyone. Only the settings screen's process does this, never the engine.
 */
class UpdateManager(private val activity: Activity) {
    private data class Release(val version: String, val fileName: String, val url: String, val size: Long)

    private var worker: Thread? = null
    @Volatile private var live: HttpURLConnection? = null
    @Volatile private var canceled = false
    private var dialog: AlertDialog? = null
    private var bar: ProgressBar? = null
    private var percentText: TextView? = null
    private var shownPercent = -1

    /** The version waiting for the install permission, when the person returns from that screen. */
    private var waiting: Release? = null

    fun checkOnStart() {
        if (checkedThisRun) return
        checkedThisRun = true
        Thread({
            val release = try {
                fetch()
            } catch (e: Exception) {
                Log.d(TAG, "update lookup failed", e)
                null
            } ?: return@Thread
            activity.runOnUiThread { onRelease(release) }
        }, "Phonon update").start()
    }

    /** Call from the activity's onResume: picks up after the install permission screen. */
    fun onResume() {
        val release = waiting ?: return
        waiting = null
        if (canInstall()) start(release)
    }

    /** Call from the activity's onDestroy. */
    fun release() {
        cancel()
        dismiss()
    }

    private fun fetch(): Release? {
        val connection = (URL(API).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Phonon-Android")
            connectTimeout = 10000
            readTimeout = 10000
        }
        try {
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = json.optString("tag_name").removePrefix("v").trim()
            val assets = json.optJSONArray("assets") ?: return null
            if (version.isEmpty()) return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return Release(version, name, asset.optString("browser_download_url"), asset.optLong("size"))
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun onRelease(release: Release) {
        if (activity.isFinishing || activity.isDestroyed) return
        val mine = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        if (!isNewer(release.version, mine)) {
            // Up to date: an APK kept from the last update is 130 MB for nothing.
            folder().deleteRecursively()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(
                activity.getString(
                    R.string.update_body, mine, release.version,
                    Formatter.formatShortFileSize(activity, release.size),
                ),
            )
            .setPositiveButton(android.R.string.yes) { _, _ -> start(release) }
            .setNegativeButton(android.R.string.no, null)
            .show()
    }

    private fun start(release: Release) {
        if (!canInstall()) {
            askPermission(release)
            return
        }
        val target = File(folder(), release.fileName)
        if (target.isFile && target.length() == release.size) install(target) else download(release, target)
    }

    private fun folder() = File(activity.cacheDir, "updates").apply { mkdirs() }

    private fun download(release: Release, target: File) {
        val part = File(target.path + ".part")
        folder().listFiles()?.forEach { if (it != part) it.delete() }
        canceled = false
        shownPercent = -1
        showProgress()
        worker = Thread({
            val done = try {
                fetchFile(release, part)
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                false
            }
            val ready = done && !canceled && part.renameTo(target)
            if (!ready) part.delete()
            val warn = !ready && !canceled
            activity.runOnUiThread {
                dismiss()
                if (ready) install(target)
                if (warn) fail()
            }
        }, "Phonon download").also { it.start() }
    }

    private fun fetchFile(release: Release, part: File): Boolean {
        val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Phonon-Android")
            connectTimeout = 15000
            readTimeout = 20000
        }
        live = connection
        try {
            if (connection.responseCode != 200) return false
            var got = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (!canceled) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        got += read
                        if (release.size > 0) {
                            val percent = ((got * 100) / release.size).toInt().coerceIn(0, 100)
                            activity.runOnUiThread { showPercent(percent) }
                        }
                    }
                }
            }
            return !canceled && got == release.size
        } finally {
            live = null
            connection.disconnect()
        }
    }

    private fun showProgress() {
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        // The percentage is text as well, because a bar alone says nothing to a screen reader.
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(activity).apply { text = activity.getString(R.string.update_percent, 0) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(progress)
            addView(label)
        }
        bar = progress
        percentText = label
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_download_title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancel() }
            .show()
            // A big download should not stop because the screen went off.
            .also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    private fun showPercent(percent: Int) {
        val label = percentText ?: return
        if (percent == shownPercent) return
        shownPercent = percent
        bar?.progress = percent
        val text = activity.getString(R.string.update_percent, percent)
        label.text = text
        label.announceForAccessibility(text)
    }

    /** Cuts the connection too, since a blocked network read does not notice a flag. */
    private fun cancel() {
        canceled = true
        val open = live
        worker?.interrupt()
        if (open != null) Thread({ open.disconnect() }, "Phonon cancel").start()
    }

    private fun dismiss() {
        dialog?.let { if (it.isShowing) it.dismiss() }
        dialog = null
        bar = null
        percentText = null
    }

    private fun fail() {
        Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
    }

    private fun canInstall() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun askPermission(release: Release) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_body)
            .setPositiveButton(R.string.update_permission_action) { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                try {
                    waiting = release
                    activity.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no install permission screen", e)
                    waiting = null
                    fail()
                }
            }
            .setNegativeButton(android.R.string.no, null)
            .show()
    }

    private fun install(file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.e(TAG, "could not open the installer", e)
            fail()
        }
    }

    companion object {
        private const val TAG = "PhononUpdate"
        private const val API = "https://api.github.com/repos/vrivrixd/phonon-android/releases/latest"

        /** One lookup per run. */
        @Volatile private var checkedThisRun = false

        /** Compares number by number, so 1.10 is newer than 1.9. */
        fun isNewer(remote: String, local: String): Boolean {
            val there = numbers(remote)
            val here = numbers(local)
            for (i in 0 until maxOf(there.size, here.size)) {
                val a = there.getOrElse(i) { 0 }
                val b = here.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        private fun numbers(version: String) =
            version.split(".").map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    }
}
