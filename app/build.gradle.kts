plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kevxngg.monitorroot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kevxngg.monitorroot"
        minSdk = 26
        targetSdk = 34
        // Internal build number (always increases): used by Android and the updater
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = ciBuild
        // Visible version (2.2, 2.3…): taken from the VERSION file at the repository root
        versionName = rootProject.file("VERSION").readText().trim()
    }

    // Signing: the keys live in GitHub Secrets, never in the repository.
    // GitHub Actions signs afterwards with key rotation (see build.yml).
    // Without them (for example, when building on another PC) the release APK is unsigned.
    val oldKeystore = System.getenv("SIGNING_OLD_KEYSTORE_FILE")
    signingConfigs {
        if (oldKeystore != null) {
            create("monitor") {
                storeFile = file(oldKeystore)
                storePassword = System.getenv("SIGNING_OLD_PASS")
                keyAlias = "monitor"
                keyPassword = System.getenv("SIGNING_OLD_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("monitor")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Shizuku: ADB-level command access without root
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
