import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.cambraia.openherdr"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.cambraia.openherdr"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        // sherpa-onnx ships native libraries per ABI; phones are arm64, emulators x86_64.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Release signing comes from a properties file kept outside the repository
    // (OPENHERDR_KEYSTORE_PROPERTIES, default ~/.config/openherdr-release/keystore.properties).
    // Without it, release builds fall back to the debug key so anyone can build and install.
    val keystoreFile = file(
        System.getenv("OPENHERDR_KEYSTORE_PROPERTIES")
            ?: "${System.getProperty("user.home")}/.config/openherdr-release/keystore.properties",
    )
    val releaseSigning = keystoreFile.takeIf { it.isFile }?.let { f ->
        val props = Properties().apply { f.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            // Integration tests read OPENHERDR_IT_* from the environment; see AGENTS.md.
            it.environment(System.getenv().filterKeys { key -> key.startsWith("OPENHERDR_IT") })
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsch)
    implementation(libs.profileinstaller)
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.markdown.m3)
    // Speech recognition runtime; fetch with scripts/fetch-sherpa.sh (not committed, ~50 MB).
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
