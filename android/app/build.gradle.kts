import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load local.properties for per-developer secrets.
// File is gitignored — each developer has their own copy.
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
        versionCode = 1
        versionName = "1.0"

        // Inject secrets at compile time from local.properties.
        // Read in Kotlin via BuildConfig.BACKEND_URL / BuildConfig.API_SECRET.
        buildConfigField(
            "String", "BACKEND_URL",
            "\"${localProp("BACKEND_URL", "https://example.invalid")}\""
        )
        buildConfigField(
            "String", "API_SECRET",
            "\"${localProp("API_SECRET", "")}\""
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
