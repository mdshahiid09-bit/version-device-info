package com.shahiid.versioninfo.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shahiid.versioninfo.model.AppVersionInfo
import com.shahiid.versioninfo.model.DeviceInfoProvider
import com.shahiid.versioninfo.model.DeviceDetails
import com.shahiid.versioninfo.updater.UpdateInfo
import com.shahiid.versioninfo.updater.UpdateManager
import com.shahiid.versioninfo.updater.UpdateState
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPrefs = remember {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    val defaultRepo = "mdshahiid09-bit/version-device-info"
    var targetRepo by remember {
        mutableStateOf(sharedPrefs.getString("target_repo", defaultRepo) ?: defaultRepo)
    }

    var appVersion by remember {
        mutableStateOf(DeviceInfoProvider.getAppVersionInfo(context))
    }
    val deviceDetails = remember {
        DeviceInfoProvider.getDeviceDetails()
    }

    var showEditRepoDialog by remember { mutableStateOf(false) }
    var showObtainiumMissingDialog by remember { mutableStateOf(false) }
    var tempRepoInput by remember { mutableStateOf(targetRepo) }

    // In-App Updater State
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Version & Device Info",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        appVersion = DeviceInfoProvider.getAppVersionInfo(context)
                        updateState = UpdateState.Idle
                        Toast.makeText(context, "Refreshed info", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Info"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // 1. Prominent App Version Banner Card
            VersionBannerCard(appVersion = appVersion)

            // 2. In-App Direct Auto-Updater Card (No Obtainium required!)
            InAppUpdaterCard(
                targetRepo = targetRepo,
                currentVersion = appVersion.versionName,
                updateState = updateState,
                onCheckForUpdates = {
                    coroutineScope.launch {
                        updateState = UpdateState.Checking
                        updateState = UpdateManager.checkForUpdates(targetRepo, appVersion.versionName)
                    }
                },
                onDownloadUpdate = { info ->
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
                onInstallNow = { file ->
                    UpdateManager.promptInstall(context, file)
                },
                onDismissState = {
                    updateState = UpdateState.Idle
                }
            )

            // 3. Obtainium & Release Actions Card
            ObtainiumActionCard(
                targetRepo = targetRepo,
                onEditRepo = {
                    tempRepoInput = targetRepo
                    showEditRepoDialog = true
                },
                onOpenObtainium = {
                    val cleanRepo = targetRepo.trim().removePrefix("https://github.com/").trim('/')
                    val deepLink = "obtainium://app/https://github.com/$cleanRepo"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        showObtainiumMissingDialog = true
                    }
                },
                onOpenGitHubReleases = {
                    val cleanRepo = targetRepo.trim().removePrefix("https://github.com/").trim('/')
                    val webUrl = "https://github.com/$cleanRepo/releases"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                onCopyDeepLink = {
                    val cleanRepo = targetRepo.trim().removePrefix("https://github.com/").trim('/')
                    val deepLink = "obtainium://app/https://github.com/$cleanRepo"
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Obtainium Deep Link", deepLink)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied Obtainium deep link to clipboard!", Toast.LENGTH_SHORT).show()
                }
            )

            // 4. Device Info Card
            DeviceInfoCard(deviceDetails = deviceDetails)

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Dialog: Edit Target GitHub Repository
    if (showEditRepoDialog) {
        AlertDialog(
            onDismissRequest = { showEditRepoDialog = false },
            title = { Text("Configure Repository") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Specify the GitHub owner/repo for in-app updates and Obtainium linking.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = tempRepoInput,
                        onValueChange = { tempRepoInput = it },
                        label = { Text("Owner / Repository") },
                        placeholder = { Text("username/repository") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cleaned = tempRepoInput.trim()
                        .removePrefix("https://github.com/")
                        .trim('/')
                    if (cleaned.isNotBlank()) {
                        targetRepo = cleaned
                        sharedPrefs.edit().putString("target_repo", cleaned).apply()
                    }
                    showEditRepoDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    targetRepo = defaultRepo
                    sharedPrefs.edit().putString("target_repo", defaultRepo).apply()
                    showEditRepoDialog = false
                }) {
                    Text("Reset to Default")
                }
            }
        )
    }

    // Dialog: Obtainium Not Installed Warning & Download Helper
    if (showObtainiumMissingDialog) {
        AlertDialog(
            onDismissRequest = { showObtainiumMissingDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Obtainium Not Detected") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Obtainium is not installed. You can still use the 'Check for Updates' button right here inside the app!",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Or tap 'Get Obtainium' to install it from GitHub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showObtainiumMissingDialog = false
                    val downloadUri = Uri.parse("https://github.com/ImranR98/Obtainium/releases")
                    context.startActivity(Intent(Intent.ACTION_VIEW, downloadUri))
                }) {
                    Text("Get Obtainium")
                }
            },
            dismissButton = {
                TextButton(onClick = { showObtainiumMissingDialog = false }) {
                    Text("Dismiss")
                }
            }
        )
    }
}

/**
 * In-App Direct Auto-Updater Card
 */
@Composable
fun InAppUpdaterCard(
    targetRepo: String,
    currentVersion: String,
    updateState: UpdateState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: (UpdateInfo) -> Unit,
    onInstallNow: (File) -> Unit,
    onDismissState: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "In-App Software Update",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Direct in-app updating (No extra app needed)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Main "Check for Updates" Button
            Button(
                onClick = onCheckForUpdates,
                enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (updateState is UpdateState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "Checking GitHub Releases...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Check for Updates",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // State Responses: Update Available
            AnimatedVisibility(
                visible = updateState is UpdateState.UpdateAvailable,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (updateState is UpdateState.UpdateAvailable) {
                    val info = updateState.updateInfo
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NewReleases,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "New Update Available!",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                SuggestionChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = info.tagName,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                )
                            }

                            // Details: Version & File Size
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Download Size:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = info.fileSizeMb,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Release notes snippet
                            if (info.releaseNotes.isNotBlank()) {
                                Text(
                                    text = "Release Notes: ${info.releaseNotes.take(120)}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Button(
                                onClick = { onDownloadUpdate(info) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Download & Install (${info.fileSizeMb})",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // State: Downloading with Progress Bar
            AnimatedVisibility(visible = updateState is UpdateState.Downloading) {
                if (updateState is UpdateState.Downloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Downloading update...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${updateState.downloadedMb} / ${updateState.totalMb} (${updateState.progressPercent}%)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { updateState.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                        )
                    }
                }
            }

            // State: Ready to Install
            AnimatedVisibility(visible = updateState is UpdateState.ReadyToInstall) {
                if (updateState is UpdateState.ReadyToInstall) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "✓ Download Complete! Click below if installer didn't open automatically:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2E7D32)
                        )
                        Button(
                            onClick = { onInstallNow(updateState.apkFile) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E7D32)
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Install Update Now", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // State: Up To Date
            AnimatedVisibility(visible = updateState is UpdateState.UpToDate) {
                if (updateState is UpdateState.UpToDate) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFD7FAD9),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32)
                            )
                            Column {
                                Text(
                                    text = "You're up to date!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0A3810)
                                )
                                Text(
                                    text = "v${updateState.currentVersion} is the latest version available.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF0A3810).copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }

            // State: Error
            AnimatedVisibility(visible = updateState is UpdateState.Error) {
                if (updateState is UpdateState.Error) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Update Check Failed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = updateState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            IconButton(onClick = onDismissState) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Prominent App Version Banner Card
 */
@Composable
fun VersionBannerCard(
    appVersion: AppVersionInfo,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ACTIVE INSTALLED BUILD",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text(
                text = "v${appVersion.versionName}",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center
            )

            Text(
                text = "(Build ${appVersion.versionCode})",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = appVersion.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    modifier = Modifier.padding(end = 6.dp)
                )
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = appVersion.buildType.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        }
    }
}

/**
 * Obtainium & Release Actions Card
 */
@Composable
fun ObtainiumActionCard(
    targetRepo: String,
    onEditRepo: () -> Unit,
    onOpenObtainium: () -> Unit,
    onOpenGitHubReleases: () -> Unit,
    onCopyDeepLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Obtainium Integration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "One-click install & Obtainium auto-updating",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Target Repository",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = targetRepo,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(
                        onClick = onEditRepo,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Repository",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Button(
                onClick = onOpenObtainium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Add / Check in Obtainium",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onOpenGitHubReleases,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInBrowser,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open GitHub Releases Page",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            TextButton(
                onClick = onCopyDeepLink,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Copy Obtainium Deep Link",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/**
 * Device Info Card
 */
@Composable
fun DeviceInfoCard(
    deviceDetails: DeviceDetails,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Device Information",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            InfoRow(label = "Model", value = "${deviceDetails.manufacturer} ${deviceDetails.model}")
            InfoRow(label = "Android Version", value = "Android ${deviceDetails.androidVersion}")
            InfoRow(label = "SDK API Level", value = "API ${deviceDetails.sdkInt}")
            InfoRow(label = "Security Patch", value = deviceDetails.securityPatch)
            InfoRow(label = "Brand / Board", value = "${deviceDetails.brand} (${deviceDetails.board})")
            InfoRow(label = "Supported ABIs", value = deviceDetails.supportedAbis)
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}
