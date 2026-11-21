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

    private fun cleanRepo(repoPath: String): String {
        return repoPath.trim()
            .replace("https://", "")
            .replace("http://", "")
            .replace("github.com/", "")
            .replace("www.github.com/", "")
            .trim('/')
    }

    suspend fun checkForUpdates(
        repoPath: String,
        currentVersion: String
    ): UpdateState = withContext(Dispatchers.IO) {
        val cleanRepo = cleanRepo(repoPath)
        if (cleanRepo.isBlank() || !cleanRepo.contains("/")) {
            return@withContext UpdateState.Error("Invalid repository format: '$cleanRepo'. Expected 'owner/repo'.")
        }

        var tagName = ""
        var releaseName = ""
        var releaseNotes = ""
        var apkUrl = ""
        var apkName = ""
        var apkSizeBytes: Long = 0

        // Strategy 1: GitHub API
        try {
            val apiUrl = "https://api.github.com/repos/$cleanRepo/releases/latest"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "VersionDeviceInfoApp/1.0")
                connectTimeout = 10000
                readTimeout = 10000
            }

            if (conn.responseCode == 200) {
                val jsonString = conn.inputStream.bufferedReader().use { it.readText() }
                val releaseObj = JSONObject(jsonString)
                tagName = releaseObj.optString("tag_name", "").trim()
                releaseName = releaseObj.optString("name", tagName)
                releaseNotes = releaseObj.optString("body", "")
                val assets = releaseObj.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "")
                            apkName = name
                            apkSizeBytes = asset.optLong("size", 0L)
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to strategy 2 if API is rate limited or fails
        }

        // Strategy 2: Web 302 Redirect Fallback (Never rate limited by GitHub)
        if (tagName.isBlank() || apkUrl.isBlank()) {
            try {
                val webUrl = "https://github.com/$cleanRepo/releases/latest"
                val webConn = (URL(webUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val redirectUrl = webConn.getHeaderField("Location") ?: ""
                if (redirectUrl.contains("/releases/tag/")) {
                    tagName = redirectUrl.substringAfter("/releases/tag/").trim('/')
                    releaseName = "Release $tagName"
                    apkName = "version-info-$tagName.apk"
                    apkUrl = "https://github.com/$cleanRepo/releases/download/$tagName/$apkName"
                    try {
                        val headConn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "HEAD"
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", "VersionDeviceInfoApp/1.0")
                            connectTimeout = 8000
                        }
                        apkSizeBytes = headConn.contentLengthLong
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                return@withContext UpdateState.Error("Network error: ${e.localizedMessage ?: "Failed to connect to GitHub"}")
            }
        }

        if (tagName.isBlank() || apkUrl.isBlank()) {
            return@withContext UpdateState.Error("No releases or APK found on GitHub for '$cleanRepo'.")
        }

        val sizeInMb = if (apkSizeBytes > 0) {
            String.format(Locale.US, "%.1f MB", apkSizeBytes / (1024.0 * 1024.0))
        } else {
            "~11 MB"
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

        // Follow redirects in a loop (GitHub to AWS S3 CDN)
        var currentUrl = updateInfo.downloadUrl
        var connection: HttpURLConnection
        var redirectCount = 0

        while (true) {
            connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "VersionDeviceInfoApp/1.0")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308
            ) {
                currentUrl = connection.getHeaderField("Location") ?: break
                redirectCount++
                if (redirectCount > 6) break
            } else {
                break
            }
        }

        val totalLength = connection.contentLengthLong.let { if (it > 0) it else updateInfo.sizeBytes }
        val totalMbStr = if (totalLength > 0) {
            String.format(Locale.US, "%.1f MB", totalLength / (1024.0 * 1024.0))
        } else {
            updateInfo.fileSizeMb
        }

        connection.inputStream.use { input ->
            FileOutputStream(destinationFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    val percent = if (totalLength > 0) ((totalBytesRead * 100) / totalLength).toInt().coerceIn(0, 100) else 0
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
