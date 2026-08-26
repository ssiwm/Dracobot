import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Google Services stosowany warunkowo — wymaga app/google-services.json (nie commitowany).
// Bez pliku apka buduje sie i dziala, tylko push FCM jest nieaktywny.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "eu.draconest.hermesbots"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.draconest.hermesbots"
        minSdk = 26
        targetSdk = 34
        versionCode = 26
        versionName = "1.8.1"
    }

    signingConfigs {
        create("release") {
            // Sekrety z keystore.properties (NIE commitowany — patrz .gitignore).
            // Format pliku: storeFile=/sciezka/do.keystore, storePassword=..., keyAlias=..., keyPassword=...
            val ksProps = Properties()
            val ksFile = rootProject.file("keystore.properties")
            if (ksFile.exists()) {
                ksProps.load(ksFile.inputStream())
                storeFile = file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Lucide icons (SVG -> vector drawables) — styl ikon jak w Grok/nowoczesnych apkach
    implementation("com.composables:icons-lucide-android:2.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.firebase:firebase-messaging-ktx:24.0.2")
}
