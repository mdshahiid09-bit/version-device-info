package com.shahiid.versioninfo.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shahiid.versioninfo.model.AppVersionInfo
import com.shahiid.versioninfo.model.DeviceInfoProvider
import com.shahiid.versioninfo.model.DeviceDetails

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

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
                        Toast.makeText(context, "Refreshed Version Info", Toast.LENGTH_SHORT).show()
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            // 2. Obtainium & Release Actions Card
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

            // 3. Device Info Card
            DeviceInfoCard(deviceDetails = deviceDetails)

            // 4. Update Verification Guide Card
            UpdateTestingGuideCard()

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
                        text = "Specify the GitHub owner/repo for Obtainium deep linking and release tracking.",
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
                        text = "Obtainium is not installed on this device or does not handle the 'obtainium://' URL scheme.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "You can download the latest Obtainium APK directly from its official GitHub repository.",
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
 * 1. Prominent App Version Banner Card
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
            // Live Status Pill
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

            // Prominent Version & Build Text
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

            // Metadata Chips Row
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
 * 2. Obtainium & Release Actions Card
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
                            text = "One-click install & seamless auto-updates",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Target Repository Indicator with Edit Action
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

            // Primary Action Button: Add / Check in Obtainium
            Button(
                onClick = onOpenObtainium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
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

            // Secondary Action Button: Open GitHub Releases
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
                    text = "Open GitHub Releases",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Deep Link Copy helper
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
 * 3. Device Info Card
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

            // Spec Rows
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

/**
 * 4. Update Verification Guide Card
 */
@Composable
fun UpdateTestingGuideCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "How to Test Auto-Updating",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            StepRow(
                step = "1",
                title = "Install Initial Release",
                description = "Add this app to Obtainium using the button above and install v1.0.0."
            )
            StepRow(
                step = "2",
                title = "Bump Version in Code",
                description = "Update versionName = \"1.0.1\" and versionCode = 2 in app/build.gradle.kts."
            )
            StepRow(
                step = "3",
                title = "Push Git Tag",
                description = "Run: git tag v1.0.1 && git push origin v1.0.1 to trigger GitHub Actions."
            )
            StepRow(
                step = "4",
                title = "Check Updates in Obtainium",
                description = "Open Obtainium, tap check updates, and install. The banner will immediately reflect v1.0.1!"
            )
        }
    }
}

@Composable
private fun StepRow(
    step: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
