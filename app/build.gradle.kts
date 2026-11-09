import java.net.URI
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ── Build inputs ────────────────────────────────────────────────────────────
// Every value below is looked up in local.properties, then Gradle properties
// (-P or ~/.gradle/gradle.properties), then the environment. Nothing secret is
// ever committed: Firebase client values default to EMPTY, which turns push off.
// The update-manifest key is public and pinned below by default.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun buildInput(name: String): String? =
    localProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable(name).orNull?.trim()?.takeIf { it.isNotEmpty() }

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val baseUrl = (buildInput("CHAT_BASE_URL") ?: "https://messenger.digitronics.app").trimEnd('/')
val appLinkHost = URI(baseUrl).host
val appVersionCode = buildInput("VERSION_CODE")?.toInt() ?: 1
val appVersionName = buildInput("VERSION_NAME") ?: "1.0.0"

// Short commit of the checkout, or "unknown" outside a Git work tree.
// Public half of the Worker's production update-manifest signing key
// (ANDROID_MANIFEST_SIGNING_JWK, key id "k1"): X.509 SubjectPublicKeyInfo DER,
// base64. A PUBLIC key, not a secret; the private half never leaves the Worker.
// Override with -PMANIFEST_PUBLIC_KEY_X509_B64=… (and MANIFEST_KEY_ID) to rotate.
val defaultManifestPublicKey =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8oVVuTE2xLFVgLe2mVcSeT2VRGIaFZpv8g+PCTzbM9shik1v7RPOXDJbyKsxuus2ZuIKzHiLs5O2UV3VfYL11A=="

val gitCommit: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.matches(Regex("^[0-9a-f]{4,40}$")) } ?: "unknown"

android {
    namespace = "com.digitronics.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.digitronics.chat"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "BASE_URL", quoted(baseUrl))
        buildConfigField("String", "RELEASE_PACKAGE_ID", quoted("com.digitronics.chat"))
        buildConfigField("String", "AUTH_CLIENT_ID", quoted("digitronics-chat-android"))
        buildConfigField("String", "RELEASE_CHANNEL", quoted("stable"))
        buildConfigField("String", "GIT_COMMIT", quoted(gitCommit))
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(buildInput("FIREBASE_PROJECT_ID") ?: ""))
        buildConfigField("String", "FIREBASE_APP_ID", quoted(buildInput("FIREBASE_APP_ID") ?: ""))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(buildInput("FIREBASE_API_KEY") ?: ""))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted(buildInput("FIREBASE_SENDER_ID") ?: ""))
        buildConfigField(
            "String",
            "MANIFEST_PUBLIC_KEY_X509_B64",
            quoted(buildInput("MANIFEST_PUBLIC_KEY_X509_B64") ?: defaultManifestPublicKey),
        )
        buildConfigField("String", "MANIFEST_KEY_ID", quoted(buildInput("MANIFEST_KEY_ID") ?: "k1"))
        manifestPlaceholders["appLinkHost"] = appLinkHost
    }

    // Release signing comes ONLY from the environment or Gradle properties.
    // With any value missing, assembleRelease produces an unsigned APK.
    val releaseStoreFile = buildInput("RELEASE_STORE_FILE")
    val releaseStorePassword = buildInput("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = buildInput("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = buildInput("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { it != null }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Digitronics Chat Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Digitronics Chat")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Version-bump nags: versions are pinned to what compiles against SDK 35.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
