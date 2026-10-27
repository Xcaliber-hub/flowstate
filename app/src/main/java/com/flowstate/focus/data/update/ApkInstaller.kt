package com.flowstate.focus.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads a release APK with the system DownloadManager and hands it to the package installer. */
@Singleton
class ApkInstaller @Inject constructor(@ApplicationContext private val context: Context) {

    private val dm = context.getSystemService(DownloadManager::class.java)

    private fun targetFile(version: String): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Flowstate-$version.apk")

    /**
     * Downloads [release], reporting progress in 0..1 to [onProgress]. Returns the APK file, or null if
     * the download failed. A file already downloaded for this version is reused.
     */
    suspend fun download(release: ReleaseInfo, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val file = targetFile(release.version)
        if (file.exists() && release.apkSizeBytes > 0 && file.length() == release.apkSizeBytes) {
            onProgress(1f); return@withContext file
        }
        // Remove leftovers from older versions so updates never pile up in storage.
        file.parentFile?.listFiles { f -> f.name.startsWith("Flowstate-") && f.name.endsWith(".apk") }?.forEach { it.delete() }

        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Flowstate ${release.version}")
            .setDescription("Downloading update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file))
        val id = dm.enqueue(request)

        while (true) {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return@withContext null
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { onProgress(1f); return@withContext file }
                    DownloadManager.STATUS_FAILED -> { dm.remove(id); return@withContext null }
                }
            }
            delay(300)
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /** Android 8+ asks the user once to allow installs from Flowstate. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Opens the system installer. The update keeps all data as long as the APK has the same signing key. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
