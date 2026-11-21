package com.shahiid.versioninfo.model

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.shahiid.versioninfo.BuildConfig

data class AppVersionInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val buildType: String
)

data class DeviceDetails(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val product: String,
    val device: String,
    val board: String,
    val hardware: String,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String,
    val supportedAbis: String,
    val fingerprint: String
)

object DeviceInfoProvider {

    fun getAppVersionInfo(context: Context): AppVersionInfo {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val vName = pInfo.versionName ?: BuildConfig.VERSION_NAME
            val vCode = PackageInfoCompat.getLongVersionCode(pInfo)
            AppVersionInfo(
                versionName = vName,
                versionCode = vCode,
                packageName = context.packageName,
                buildType = BuildConfig.BUILD_TYPE
            )
        } catch (e: Exception) {
            AppVersionInfo(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                packageName = context.packageName,
                buildType = BuildConfig.BUILD_TYPE
            )
        }
    }

    fun getDeviceDetails(): DeviceDetails {
        val secPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH
        } else {
            "N/A"
        }
        return DeviceDetails(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            brand = Build.BRAND.replaceFirstChar { it.uppercase() },
            product = Build.PRODUCT,
            device = Build.DEVICE,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = secPatch,
            supportedAbis = Build.SUPPORTED_ABIS.joinToString(", "),
            fingerprint = Build.FINGERPRINT
        )
    }
}
