# Version & Device Info

[![Add to Obtainium](https://img.shields.io/badge/Obtainium-Add%20App-blue?logo=android)](obtainium://app/https://github.com/mdshahiid09-bit/version-device-info)
[![GitHub Release](https://img.shields.io/github/v/release/mdshahiid09-bit/version-device-info?style=flat&color=success)](https://github.com/mdshahiid09-bit/version-device-info/releases)
[![Build Status](https://img.shields.io/github/actions/workflow/status/mdshahiid09-bit/version-device-info/build-release.yml?branch=main)](https://github.com/mdshahiid09-bit/version-device-info/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A lightweight, modern native Android application built with **Kotlin** and **Jetpack Compose (Material 3)**. Specifically designed to test **GitHub Releases**, **APK build pipelines**, and **seamless auto-updating via Obtainium**.

---

## 📱 Features

- **Prominent Version Banner:** Displays the live installed version (e.g. `v1.0.0 (Build 1)`) prominently at the top of the screen so that updates and version bumps can be visually verified instantaneously.
- **Hardware & Device Specs:**
  - Device Model & Manufacturer
  - Android OS Version & SDK API Level
  - Security Patch Level
  - Brand, Product, & Board Codename
  - Supported CPU ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`)
- **Obtainium Deep Linking:**
  - **Button 1: "Add / Check in Obtainium"**: Launches `obtainium://app/https://github.com/<GITHUB_USER>/<REPO_NAME>` to add or update the app in Obtainium with 1 tap.
  - **Button 2: "Open GitHub Releases"**: Direct web fallback to the repository releases page.
  - **Button 3: "Copy Deep Link"**: Convenient clipboard copy for manual entry or sharing.
  - **Configurable Repository**: Easily change or test alternate repositories directly in the app.
- **Material 3 Design:** Dynamic color support on Android 12+, clean card-based layout, and automatic dark/light theme support.
- **Zero Paid Toolchains:** 100% native Kotlin / Gradle build workflow that runs smoothly on standard GitHub Actions `ubuntu-latest` runners.
- **Guaranteed Installable Release APK:** Preconfigured Gradle release signing configuration that automatically falls back to debug signing when release credentials are not provided, preventing `INSTALL_PARSE_FAILED_NO_CERTIFICATES` errors.

---

## 🚀 Quick Setup with Obtainium

You can add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) in two ways:

### 1. One-Click Badge
Tap this badge on your Android device (with Obtainium installed):

[![Add to Obtainium](https://img.shields.io/badge/Obtainium-Add%20App-blue?logo=android)](obtainium://app/https://github.com/mdshahiid09-bit/version-device-info)

### 2. Manual URL Entry
1. Open Obtainium.
2. Tap **Add App**.
3. Paste the repository URL:
   ```
   https://github.com/mdshahiid09-bit/version-device-info
   ```
4. Tap **Add**. Obtainium will detect the latest release APK automatically.

---

## 🧪 Step-by-Step: How to Test Auto-Updating

Follow this checklist to verify GitHub Releases and Obtainium update flow from end to end:

### Step 1: Install Version 1.0.0
1. Trigger the initial build by creating tag `v1.0.0` or trigger the GitHub Actions workflow manually.
2. In Obtainium, add the app and tap **Install**.
3. Launch the app and verify the top banner displays:
   ```
   v1.0.0
   (Build 1)
   ```

### Step 2: Bump the Version in Code
Open `app/build.gradle.kts` and update `versionCode` and `versionName`:
```kotlin
defaultConfig {
    applicationId = "com.shahiid.versioninfo"
    minSdk = 26
    targetSdk = 35
    versionCode = 2          // <-- Bumped
    versionName = "1.0.1"    // <-- Bumped
    // ...
}
```

### Step 3: Push a New Git Tag
Commit your change and push a new release tag:
```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to v1.0.1"
git tag v1.0.1
git push origin main
git push origin v1.0.1
```

### Step 4: GitHub Actions Builds the APK
The `.github/workflows/build-release.yml` workflow triggers automatically:
1. Compiles the release APK (`assembleRelease`).
2. Generates `version-info-v1.0.1.apk`.
3. Creates a new GitHub Release with download links and SHA-256 checksums.

### Step 5: Verify Seamless Update in Obtainium
1. Open **Obtainium** on your device.
2. Tap **Check for Updates** (or wait for the periodic background check).
3. Obtainium detects `v1.0.1`.
4. Tap **Update** and confirm the installation.
5. Re-open **Version & Device Info** and verify the banner now reads:
   ```
   v1.0.1
   (Build 2)
   ```

---

## 🛠️ Project Structure

```
version-device-info/
├── .github/
│   └── workflows/
│       └── build-release.yml       # GitHub Actions CI/CD release workflow
├── app/
│   ├── build.gradle.kts            # Application Gradle config & signing
│   ├── proguard-rules.pro          # ProGuard rules
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml # App manifest with query declarations
│           ├── java/com/shahiid/versioninfo/
│           │   ├── MainActivity.kt # Edge-to-edge Compose activity
│           │   ├── model/
│           │   │   └── DeviceInfo.kt # Device & app version provider
│           │   └── ui/
│           │       ├── screens/
│           │       │   └── HomeScreen.kt # Material 3 home screen UI
│           │       └── theme/
│           │           ├── Color.kt
│           │           ├── Theme.kt
│           │           └── Type.kt
│           └── res/                # XML drawables, themes, and strings
├── gradle/
│   ├── libs.versions.toml          # Centralized dependency catalog
│   └── wrapper/                    # Gradle wrapper binaries
├── build.gradle.kts                # Root project build file
├── settings.gradle.kts             # Gradle settings
└── README.md                       # Documentation & instructions
```

---

## 🔑 APK Release Signing

By default, the release build configuration in `app/build.gradle.kts` uses a fallback to debug signing so that every APK compiled by GitHub Actions is immediately valid and installable:

```kotlin
signingConfigs {
    create("release") {
        val keystoreFile = System.getenv("KEYSTORE_FILE")
        if (keystoreFile != null && file(keystoreFile).exists()) {
            storeFile = file(keystoreFile)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        } else {
            // Safe fallback so assembleRelease always produces a signed installable APK!
            initWith(getByName("debug"))
        }
    }
}
```

If you wish to use a custom production keystore, supply the following repository secrets in GitHub Actions:
- `KEYSTORE_FILE`: Path to keystore file
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Key alias
- `KEY_PASSWORD`: Key password

---

## 📄 License

This project is licensed under the Apache 2.0 License.
