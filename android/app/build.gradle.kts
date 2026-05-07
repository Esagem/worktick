import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Per-developer OAuth client ID lives in local.properties (gitignored).
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = ""): String =
    localProperties.getProperty(key) ?: default

android {
    namespace = "dev.surge.worktick"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.surge.worktick"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        // Inject the Google OAuth client ID + secret at compile time. Both come
        // from local.properties:
        //   GOOGLE_OAUTH_CLIENT_ID=123-abcdef.apps.googleusercontent.com
        //   GOOGLE_OAUTH_CLIENT_SECRET=GOCSPX-...
        // The Web client is "confidential," so Google requires the secret on the
        // token-exchange and refresh-grant calls. Embedding it in the APK is a
        // known tradeoff — Google still gates code minting by package + SHA-1,
        // so a secret without a matching APK signature is inert.
        buildConfigField(
            "String", "GOOGLE_OAUTH_CLIENT_ID",
            "\"${localProp("GOOGLE_OAUTH_CLIENT_ID", "")}\""
        )
        buildConfigField(
            "String", "GOOGLE_OAUTH_CLIENT_SECRET",
            "\"${localProp("GOOGLE_OAUTH_CLIENT_SECRET", "")}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Google Identity Authorization (replaces backend OAuth)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Encrypted local token storage. Stable 1.0.0 — the 1.1.0 alphas drop the
    // ValueEncryptionScheme constants we need for create().
    implementation("androidx.security:security-crypto:1.0.0")
}
