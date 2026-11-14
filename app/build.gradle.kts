import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

@Suppress("UNCHECKED_CAST")
val localSecrets: Map<String, Any?> = run {
    val file = rootProject.file("local_secrets.json")
    if (file.exists()) {
        try {
            (JsonSlurper().parse(file) as? Map<String, Any?>) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    } else {
        emptyMap()
    }
}

fun rawSecretOrDefault(key: String, defaultValue: String = ""): String {
    return (localSecrets[key]?.toString()?.takeIf { it.isNotBlank() })
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: defaultValue
}

fun secretOrDefault(key: String, defaultValue: String = ""): String {
    val raw = rawSecretOrDefault(key, defaultValue)
    val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    namespace = "com.teknologigawai.gepy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.teknologigawai.gepy"
        minSdk = 29
        targetSdk = 35
        versionCode = 660
        versionName = "6.6.0"

        manifestPlaceholders["SENTRY_DSN"] = rawSecretOrDefault("SENTRY_DSN", "")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField(
            "String",
            "FIREWORKS_MODEL",
            secretOrDefault("FIREWORKS_MODEL", "accounts/fireworks/models/deepseek-v4p1-flash")
        )
        buildConfigField("String", "GEMINI_MODEL", secretOrDefault("GEMINI_MODEL", "gemini-2.5-flash"))
        buildConfigField("String", "GEMINI_LIVE_MODEL", secretOrDefault("GEMINI_LIVE_MODEL", "gemini-3.8-live"))
        buildConfigField(
            "String",
            "GEMINI_EMBEDDING_MODEL",
            secretOrDefault("GEMINI_EMBEDDING_MODEL", "gemini-embedding-2")
        )
        buildConfigField("String", "ELEVENLABS_MODEL", secretOrDefault("ELEVENLABS_MODEL", "eleven_v4_turbo"))
        buildConfigField("String", "ELEVENLABS_VOICE_ID", secretOrDefault("ELEVENLABS_VOICE_ID", ""))
        buildConfigField("String", "AZURE_OPENAI_ENDPOINT", secretOrDefault("AZURE_OPENAI_ENDPOINT", ""))
        buildConfigField(
            "String",
            "AZURE_OPENAI_DEPLOYMENT",
            secretOrDefault("AZURE_OPENAI_DEPLOYMENT", "gpt-4o-mini")
        )
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            secretOrDefault("GOOGLE_WEB_CLIENT_ID", "")
        )
        buildConfigField(
            "String",
            "GATEWAY_BASE_URL",
            secretOrDefault("GATEWAY_BASE_URL", "")
        )
        buildConfigField(
            "String",
            "LAPTOP_TAILSCALE_HOST",
            secretOrDefault("LAPTOP_TAILSCALE_HOST", "")
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")

            // Mendukung distribusi lokal dengan kredensial bawaan (with-API) maupun input BYOK pengguna.
            buildConfigField("String", "FIREWORKS_API_KEY", secretOrDefault("FIREWORKS_API_KEY", ""))
            buildConfigField("String", "GEMINI_API_KEY", secretOrDefault("GEMINI_API_KEY", ""))
            buildConfigField("String", "GEMINI_API_KEY_2", secretOrDefault("GEMINI_API_KEY_2", ""))
            buildConfigField("String", "ELEVENLABS_API_KEY", secretOrDefault("ELEVENLABS_API_KEY", ""))
            buildConfigField("String", "AZURE_OPENAI_KEY", secretOrDefault("AZURE_OPENAI_KEY", ""))
        }
        debug {
            // Pada mode debug lokal, pengembang dapat menggunakan kunci pengujian dari local_secrets.json
            buildConfigField("String", "FIREWORKS_API_KEY", secretOrDefault("FIREWORKS_API_KEY", ""))
            buildConfigField("String", "GEMINI_API_KEY", secretOrDefault("GEMINI_API_KEY", ""))
            buildConfigField("String", "GEMINI_API_KEY_2", secretOrDefault("GEMINI_API_KEY_2", ""))
            buildConfigField("String", "ELEVENLABS_API_KEY", secretOrDefault("ELEVENLABS_API_KEY", ""))
            buildConfigField("String", "AZURE_OPENAI_KEY", secretOrDefault("AZURE_OPENAI_KEY", ""))
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
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.sentry.android)
    implementation(libs.lottie.compose)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
