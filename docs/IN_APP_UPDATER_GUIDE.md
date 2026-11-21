# 🚀 In-App GitHub Releases Auto-Update System: Complete Implementation Blueprint

This guide provides the complete, battle-tested blueprint to implement a **standalone, zero-cost In-App Auto-Update System** in any Android app using **GitHub Releases**, **Jetpack Compose (or XML Views)**, and Android's native **FileProvider**.

> **Why this system is superior:**
> - **Zero Server Cost:** Hosted entirely on GitHub Releases CDN.
> - **No 3rd-Party App Needed:** Users do not need Obtainium, F-Droid, or Google Play Store.
> - **Native Play-Store Feel:** Shows exact update size in MB, live progress bar, and triggers native Android package installer with 1 tap.
> - **Dual-Engine Checking:** Immune to GitHub API rate limits (60 req/hr) by falling back to GitHub's web 302 redirect.

---

## 🏗️ System Architecture

1. **User taps "Check for Updates"** (or app checks on start).
2. **Dual-Engine Check:**
   - Strategy 1: GitHub API (`api.github.com/repos/.../releases/latest`).
   - Strategy 2 (Fallback): Web 302 Redirect (`github.com/.../releases/latest`), zero rate limit.
3. **Version Comparison:** Compares remote semver (e.g. `1.0.4`) against `BuildConfig.VERSION_NAME` (`1.0.3`).
4. **Update Available Display:** Shows new version, size (e.g. `10.7 MB`), and changelog.
5. **Streaming Downloader:** Streams APK chunks to `cacheDir/updates/update.apk` while reporting live progress % and downloaded MB to Compose UI.
6. **Native Install:** Exposes the APK via Android's `FileProvider` and launches Android's native package installer.

---

## 📋 Step 1: Android Manifest & Permissions

In your `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 1. Internet access to query GitHub & download APK -->
    <uses-permission android:name="android.permission.INTERNET" />
    
    <!-- 2. Permission to trigger native package installer on Android 8.0+ -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

    <application ...>

        <!-- 3. FileProvider to securely expose downloaded APK to system installer -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.provider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>
</manifest>
```

### Create `res/xml/file_paths.xml`
Create `app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="cache_updates" path="updates/" />
    <external-cache-path name="external_cache_updates" path="updates/" />
    <external-files-path name="external_files_updates" path="updates/" />
</paths>
```

---

## 🔑 Step 2: Consistent Keystore Signing (`build.gradle.kts`)

> **Signature Mismatch Trap:**
> Android will refuse to install an update with error `INSTALL_FAILED_UPDATE_INCOMPATIBLE` if the signing certificate changes between versions. You must sign every release with the **exact same keystore**.

In `app/build.gradle.kts`:

```kotlin
android {
    ...
    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null && file(keystoreFile).exists()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                // Bundle a consistent debug.keystore in app directory so all CI builds match!
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

Generate `app/debug.keystore` once and commit it to git:
```bash
keytool -genkey -v -keystore app/debug.keystore -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

---

## ⚙️ Step 3: The Core Update Engine (`UpdateManager.kt`)

Create `app/src/main/java/<package>/updater/UpdateManager.kt`:

```kotlin
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
        repoPath: String, // e.g. "username/repository"
        currentVersion: String // e.g. "1.0.3"
    ): UpdateState = withContext(Dispatchers.IO) {
        val cleanRepo = cleanRepo(repoPath)
        if (cleanRepo.isBlank() || !cleanRepo.contains("/")) {
            return@withContext UpdateState.Error("Invalid repo format. Expected 'owner/repo'.")
        }

        var tagName = ""
        var releaseName = ""
        var releaseNotes = ""
        var apkUrl = ""
        var apkName = ""
        var apkSizeBytes: Long = 0

        // Strategy 1: GitHub REST API
        try {
            val apiUrl = "https://api.github.com/repos/$cleanRepo/releases/latest"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AppUpdater/1.0")
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
        } catch (_: Exception) {}

        // Strategy 2: Web 302 Redirect Fallback (Bypasses GitHub 60 req/hr rate limits!)
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
                            setRequestProperty("User-Agent", "AppUpdater/1.0")
                            connectTimeout = 8000
                        }
                        apkSizeBytes = headConn.contentLengthLong
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                return@withContext UpdateState.Error("Network error: ${e.localizedMessage}")
            }
        }

        if (tagName.isBlank() || apkUrl.isBlank()) {
            return@withContext UpdateState.Error("No releases or APK found for '$cleanRepo'.")
        }

        val sizeInMb = if (apkSizeBytes > 0) {
            String.format(Locale.US, "%.1f MB", apkSizeBytes / (1024.0 * 1024.0))
        } else {
            "~11 MB"
        }

        val remoteVer = tagName.removePrefix("v").removePrefix("V").trim()
        val localVer = currentVersion.removePrefix("v").removePrefix("V").trim()

        if (isVersionNewer(remoteVer, localVer)) {
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

        // Loop to follow 302 redirects from GitHub to AWS S3 storage
        var currentUrl = updateInfo.downloadUrl
        var connection: HttpURLConnection
        var redirectCount = 0

        while (true) {
            connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "AppUpdater/1.0")
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

        // Android 8.0+ unknown app install permission gate
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
```

---

## 🎨 Step 4: Jetpack Compose UI Component

Embed this composable inside your Settings or Home screen:

```kotlin
@Composable
fun InAppUpdaterCard(
    targetRepo: String, // "owner/repo"
    currentVersion: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(text = "Software Updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // Button: Check for updates
            Button(
                onClick = {
                    coroutineScope.launch {
                        updateState = UpdateState.Checking
                        updateState = UpdateManager.checkForUpdates(targetRepo, currentVersion)
                    }
                },
                enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (updateState is UpdateState.Checking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Checking GitHub Releases...")
                } else {
                    Icon(imageVector = Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Check for Updates")
                }
            }

            // State: Update Available
            if (updateState is UpdateState.UpdateAvailable) {
                val info = (updateState as UpdateState.UpdateAvailable).updateInfo
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "New Update: ${info.tagName}", fontWeight = FontWeight.Bold)
                        Text(text = "Download Size: ${info.fileSizeMb}", style = MaterialTheme.typography.bodyMedium)
                        
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        val apk = UpdateManager.downloadApk(context, info) { progress ->
                                            updateState = progress
                                        }
                                        updateState = UpdateState.ReadyToInstall(apk, info)
                                        UpdateManager.promptInstall(context, apk)
                                    } catch (e: Exception) {
                                        updateState = UpdateState.Error(e.localizedMessage ?: "Download failed.")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download & Install (${info.fileSizeMb})")
                        }
                    }
                }
            }

            // State: Downloading with Progress Bar
            if (updateState is UpdateState.Downloading) {
                val progress = updateState as UpdateState.Downloading
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "Downloading: ${progress.downloadedMb} / ${progress.totalMb} (${progress.progressPercent}%)")
                    LinearProgressIndicator(
                        progress = { progress.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                }
            }

            // State: Up to Date
            if (updateState is UpdateState.UpToDate) {
                Text(text = "✓ You are up to date! (v$currentVersion)", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            }

            // State: Error
            if (updateState is UpdateState.Error) {
                Text(text = "Error: ${(updateState as UpdateState.Error).message}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
```

---

## 🤖 Step 5: Automated GitHub Actions CI/CD (`.github/workflows/build-release.yml`)

Configure GitHub Actions to automatically compile and publish the signed APK whenever a new git tag is pushed:

```yaml
name: Build & Release APK

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4

      - name: Make Gradlew Executable
        run: chmod +x gradlew

      - name: Build Release APK
        run: ./gradlew assembleRelease --stacktrace

      - name: Rename APK
        run: |
          cp app/build/outputs/apk/release/app-release.apk version-info-${{ github.ref_name }}.apk

      - name: Publish GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ github.ref_name }}
          name: "Release ${{ github.ref_name }}"
          draft: false
          prerelease: false
          files: version-info-${{ github.ref_name }}.apk
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

## 🚀 How to Release Updates (Cycle)

Whenever you have new changes:
1. Open `app/build.gradle.kts`:
   - Increment `versionCode` (e.g. `1` ➡️ `2`)
   - Bump `versionName` (e.g. `"1.0.0"` ➡️ `"1.0.1"`)
2. Commit and push a tag:
   ```bash
   git commit -am "chore: bump version to v1.0.1"
   git tag v1.0.1
   git push origin main --tags
   ```
3. GitHub Actions builds the APK in ~2 minutes.
4. Your users open their app, tap **"Check for Updates"**, and update with 1 tap directly on their screen!
