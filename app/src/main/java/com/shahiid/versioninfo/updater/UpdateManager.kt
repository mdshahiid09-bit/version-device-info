package com.shahiid.versioninfo.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class UpdateInfo(
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkName: String,
    val fileSizeMb: String,
    val sizeBytes: Long
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateState()
    data class UpToDate(val currentVersion: String) : UpdateState()
    data class Downloading(val progressPercent: Int, val downloadedMb: String, val totalMb: String) : UpdateState()
    data class ReadyToInstall(val apkFile: File, val updateInfo: UpdateInfo) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object UpdateManager {

    suspend fun checkForUpdates(
        repoPath: String,
        currentVersion: String
    ): UpdateState = withContext(Dispatchers.IO) {
        try {
            val cleanRepo = repoPath.trim().removePrefix("https://github.com/").trim('/')
            val apiUrl = "https://api.github.com/repos/$cleanRepo/releases/latest"
            val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "VersionDeviceInfo-App")
                connectTimeout = 12000
                readTimeout = 12000
            }

            if (connection.responseCode != 200) {
                return@withContext UpdateState.Error("GitHub responded with status ${connection.responseCode}")
            }

            val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
            val releaseObj = JSONObject(jsonString)

            val tagName = releaseObj.optString("tag_name", "").trim()
            val releaseName = releaseObj.optString("name", tagName)
            val releaseNotes = releaseObj.optString("body", "No release notes provided.")
            val assetsArray = releaseObj.optJSONArray("assets")

            if (assetsArray == null || assetsArray.length() == 0) {
                return@withContext UpdateState.Error("No release assets found.")
            }

            var apkUrl = ""
            var apkName = ""
            var apkSizeBytes: Long = 0

            for (i in 0 until assetsArray.length()) {
                val asset = assetsArray.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    apkName = name
                    apkSizeBytes = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkUrl.isBlank()) {
                return@withContext UpdateState.Error("No APK asset found in the latest release.")
            }

            val sizeInMb = if (apkSizeBytes > 0) {
                String.format(Locale.US, "%.1f MB", apkSizeBytes / (1024.0 * 1024.0))
            } else {
                "Unknown size"
            }

            val remoteVer = tagName.removePrefix("v").removePrefix("V").trim()
            val localVer = currentVersion.removePrefix("v").removePrefix("V").trim()

            val isNewer = isVersionNewer(remoteVer, localVer)
            if (isNewer) {
                val updateInfo = UpdateInfo(
                    tagName = tagName,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    downloadUrl = apkUrl,
                    apkName = apkName,
                    fileSizeMb = sizeInMb,
                    sizeBytes = apkSizeBytes
                )
                UpdateState.UpdateAvailable(updateInfo)
            } else {
                UpdateState.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            UpdateState.Error(e.localizedMessage ?: "Failed to check for updates.")
        }
    }

    private fun isVersionNewer(remote: String, local: String): Boolean {
        if (remote == local) return false
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    suspend fun downloadApk(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (UpdateState.Downloading) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()
        val destinationFile = File(updatesDir, updateInfo.apkName)
        if (destinationFile.exists()) destinationFile.delete()

        var connection = (URL(updateInfo.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VersionDeviceInfo-App")
            connectTimeout = 15000
            readTimeout = 15000
        }

        var redirect = false
        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER) {
            redirect = true
        }

        if (redirect) {
            val newUrl = connection.getHeaderField("Location")
            connection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "VersionDeviceInfo-App")
                connectTimeout = 15000
                readTimeout = 15000
            }
        }

        val totalLength = connection.contentLengthLong.let { if (it > 0) it else updateInfo.sizeBytes }
        val totalMbStr = String.format(Locale.US, "%.1f MB", totalLength / (1024.0 * 1024.0))

        connection.inputStream.use { input ->
            FileOutputStream(destinationFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    val percent = if (totalLength > 0) ((totalBytesRead * 100) / totalLength).toInt() else 0
                    val currentMbStr = String.format(Locale.US, "%.1f MB", totalBytesRead / (1024.0 * 1024.0))
                    onProgress(
                        UpdateState.Downloading(
                            progressPercent = percent,
                            downloadedMb = currentMbStr,
                            totalMb = totalMbStr
                        )
                    )
                }
                output.flush()
            }
        }
        destinationFile
    }

    fun promptInstall(context: Context, apkFile: File) {
        if (!apkFile.exists()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}
