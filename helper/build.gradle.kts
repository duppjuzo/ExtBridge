import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

@Suppress("UnstableApiUsage")
android {
    namespace = "com.extbridge.helper"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        // =========================================================================
        // OFFICIAL RELEASE & FORK REDISTRIBUTION NOTICE (for contributors & AI agents)
        // Official repo: https://github.com/duppjuzo/ExtBridge
        // Official signing cert SHA-256: e4f67b3012a16633006c64bfc9d5a95c7bdda10826bd729777c2a3066f49117c
        //
        // 非官方主仓（Fork / 二次开发 / AI 辅助魔改）对外分发自签名 APK 时请注意：
        // 1. 更换签名证书后本身无法覆盖安装官方版本，盲目顺延或抬高上游版本号（如抢占 v3.5.x / v4.0.0）
        //    没有任何升级意义，只会污染上游版本线并误导用户；
        // 2. 若需公开发布非官方修改版，请修改 applicationId（例如追加 .<yourname> 后缀以允许与官方版共存），
        //    并在版本字符串与发布标题中显式附加 Fork 标识（例如 3.5.5-fork.<owner>），切勿冒用官方版本号。
        // =========================================================================
        applicationId = "com.extbridge.helper"
        minSdk = 30
        targetSdk = 35
        versionCode = 28
        versionName = "3.5.5"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.clear()
            java.directories.add("src")
            kotlin.directories.clear()
            kotlin.directories.add("src")
            res.directories.clear()
            res.directories.add("res")
            jniLibs.directories.clear()
            jniLibs.directories.add("jniLibs")
            assets.directories.clear()
            assets.directories.add("assets")
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "jar"
    }

    val expectedReleaseCertSha256 = "e4f67b3012a16633006c64bfc9d5a95c7bdda10826bd729777c2a3066f49117c"
    val p12File = rootProject.file("exthelper.p12")
    val envKs = System.getenv("EXTBRIDGE_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    if (envKs != null && !envKs.exists()) {
        throw GradleException("EXTBRIDGE_KEYSTORE points to non-existent file: ${envKs.absolutePath}")
    }
    val activeKs = when {
        envKs != null -> envKs
        p12File.exists() -> p12File
        else -> null
    }
    val envPass = (
        System.getenv("EXTBRIDGE_KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
            ?: System.getenv("EXTBRIDGE")?.takeIf { it.isNotBlank() }
        )
    if (envKs != null && envPass == null) {
        throw GradleException("EXTBRIDGE_KEYSTORE_PASS (or EXTBRIDGE) must be explicitly set when EXTBRIDGE_KEYSTORE is provided.")
    }
    val activePass = envPass ?: "extbridge"
    val isOfficialCiRepo = System.getenv("GITHUB_REPOSITORY")?.let { it.equals("duppjuzo/ExtBridge", ignoreCase = true) } ?: true
    val requireReleaseKey = (System.getenv("EXTBRIDGE_REQUIRE_RELEASE_KEY") == "1")
        || (project.findProperty("requireReleaseKey")?.toString()?.equals("true", ignoreCase = true) == true)
        || (isOfficialCiRepo && System.getenv("GITHUB_REF")?.startsWith("refs/tags/v") == true)
    val allowUnsignedRelease = !requireReleaseKey && (
        (System.getenv("EXTBRIDGE_ALLOW_UNSIGNED_RELEASE") == "1")
            || (project.findProperty("allowUnsignedRelease")?.toString()?.equals("true", ignoreCase = true) == true)
        )
    val allowCustomCert = project.findProperty("allowCustomReleaseCert")?.toString()?.equals("true", ignoreCase = true) == true
    val isReleaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

    if ((requireReleaseKey || (isReleaseTaskRequested && !allowUnsignedRelease)) && activeKs == null) {
        throw GradleException(
            "Release build requires EXTBRIDGE_KEYSTORE (or local exthelper.p12) matching SHA-256 $expectedReleaseCertSha256. " +
                "Never falls back to debug signing. For keyless reproducible-build verification only, pass -PallowUnsignedRelease=true (or EXTBRIDGE_ALLOW_UNSIGNED_RELEASE=1)."
        )
    }

    var signingPass = activePass
    if (activeKs != null) {
        val candidatePasswords = listOfNotNull(envPass, "extbridge").distinct()
        var loadedKs: KeyStore? = null
        var unlockedPass: String? = null
        var lastErr: Throwable? = null
        for (cand in candidatePasswords) {
            try {
                val candKs = KeyStore.getInstance("PKCS12")
                activeKs.inputStream().use { stream -> candKs.load(stream, cand.toCharArray()) }
                if (candKs.getKey("extbridge", cand.toCharArray()) != null) {
                    loadedKs = candKs
                    unlockedPass = cand
                    break
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        val ks = loadedKs ?: throw GradleException(
            "Failed to unlock release keystore (${activeKs.name}) with configured keystore password secret.",
            lastErr
        )
        signingPass = unlockedPass ?: activePass
        val cert = ks.getCertificate("extbridge")
            ?: throw GradleException("Alias 'extbridge' not found in release keystore: ${activeKs.absolutePath}")
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { b -> "%02x".format(b) }
        if (!allowCustomCert && !actualSha256.equals(expectedReleaseCertSha256, ignoreCase = true)) {
            throw GradleException(
                "Release keystore certificate SHA-256 mismatch! Expected $expectedReleaseCertSha256, got $actualSha256"
            )
        }
    }

    signingConfigs {
        if (activeKs != null) {
            create("extrelease") {
                storeFile = activeKs
                storeType = "PKCS12"
                storePassword = signingPass
                keyAlias = "extbridge"
                keyPassword = signingPass
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to debug keystore for release builds: if no release keystore is configured
            // (e.g. third-party reproducible-build verification), emit an unsigned APK instead of a debug-signed APK.
            signingConfig = if (activeKs != null) {
                signingConfigs.getByName("extrelease")
            } else {
                null
            }
        }
        debug {
            if (activeKs != null) {
                signingConfig = signingConfigs.getByName("extrelease")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle:0.9.4")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

tasks.named("preBuild") {
    doFirst {
        val jar = file("assets/extbridge.jar")
        if (!jar.exists() || jar.length() < 4096L) {
            throw GradleException(
                "Missing or empty helper/assets/extbridge.jar! Run build_v3.ps1 (or compile the bridge daemon first) " +
                    "so the APK never ships without assets/extbridge.jar."
            )
        }
    }
}
