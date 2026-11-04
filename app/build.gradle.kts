import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Load secrets.properties (falls back to secrets.defaults.properties) for BuildConfig.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
        .takeIf { it.exists() } ?: rootProject.file("secrets.defaults.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String = (secrets.getProperty(key) ?: "").trim()

val appId: String = (project.findProperty("appId") as String?) ?: "com.acme.remotesupport"
val baseVersionCode: Int = (project.findProperty("appVersionCode") as String? ?: "1").toInt()

// Release signing, from the gitignored keystore.properties. Without it, release APKs are unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Each per-ABI APK needs its own versionCode, and a 64-bit build must outrank a 32-bit one.
val abiVersionOffsets = mapOf("armeabi-v7a" to 1, "x86" to 2, "arm64-v8a" to 3, "x86_64" to 4)

android {
    // Fixed: follows the source package. Only applicationId changes with `appId`.
    namespace = "com.acme.remotesupport"
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 35
        versionCode = baseVersionCode
        versionName = project.findProperty("appVersionName") as String? ?: "0.1.0"

        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "STUN_URL", "\"${secret("STUN_URL")}\"")
        buildConfigField("String", "TURN_URL", "\"${secret("TURN_URL")}\"")
        buildConfigField("String", "TURN_USERNAME", "\"${secret("TURN_USERNAME")}\"")
        buildConfigField("String", "TURN_PASSWORD", "\"${secret("TURN_PASSWORD")}\"")

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Release builds only, so debug keeps its single app-debug.apk.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include(*abiVersionOffsets.keys.toTypedArray())
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            abiVersionOffsets[abi]?.let { output.versionCode.set(baseVersionCode * 10 + it) }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.functions)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.webrtc)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
}
