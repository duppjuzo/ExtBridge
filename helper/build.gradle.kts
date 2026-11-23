import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.extbridge.helper"
    buildToolsVersion = "37.0.0"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "com.extbridge.helper"
        minSdk = 30
        targetSdk = 35
        versionCode = 31
        versionName = "3.4.2-lite"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets.named("main") {
        manifest.srcFile("AndroidManifest.xml")
        // Keep Lite's source layout.
        java.directories.clear(); java.directories.add("src/com")
        kotlin.directories.clear(); kotlin.directories.add("src/com")
        res.directories.clear(); res.directories.add("res")
        assets.directories.clear(); assets.directories.add("assets")
        jniLibs.directories.clear()
        jniLibs.directories.add("jniLibs")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Align helper Java/Kotlin targets with miuix-nav's JVM 21 inline entry API.
// The separately packaged daemon remains at its existing Java 11 target.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4")
}

tasks.named("preBuild") {
    doFirst {
        val daemon = file("assets/extbridge.jar")
        check(daemon.exists() && daemon.length() > 4096) {
            "Missing Lite daemon. Build with powershell -File build_v3.ps1 first."
        }
    }
}
