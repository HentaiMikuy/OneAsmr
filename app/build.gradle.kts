import java.io.File
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    // Kotlin support is built into AGP 9 (no kotlin-android plugin).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing (自更新): CI (GitHub Actions) supplies a dedicated
// keystore via secrets — RELEASE_KEYSTORE_B64 (base64 of the .keystore),
// RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD — so the
// built APK keeps the SAME signature across releases and can update existing
// installs. Without those env vars (normal local dev) we fall back to the
// debug key, keeping `assembleRelease` installable out of the box. Keep the
// keystore + passwords OUT of git (see README「自动更新与发布」).
val releaseKeystoreB64: String? = System.getenv("RELEASE_KEYSTORE_B64")
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")
val useReleaseSigning = !releaseKeystoreB64.isNullOrBlank()

android {
    namespace = "com.oneasmr.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.oneasmr.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Plan Task 28: production-grade release — R8 minification +
            // resource shrinking. Signed with the dedicated CI keystore when
            // the secrets are present (RELEASE_KEYSTORE_B64 etc.), otherwise
            // with the debug key so `assembleRelease` stays installable out of
            // the box during local development.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (useReleaseSigning) {
                signingConfigs.create("ciRelease").also { cfg ->
                    val storeFile = File(project.layout.buildDirectory.get().asFile, "release.keystore")
                    if (!storeFile.exists()) {
                        storeFile.parentFile?.mkdirs()
                        storeFile.writeBytes(Base64.getDecoder().decode(releaseKeystoreB64))
                    }
                    cfg.storeFile = storeFile
                    cfg.storePassword = releaseKeystorePassword
                    cfg.keyAlias = releaseKeyAlias
                    cfg.keyPassword = releaseKeyPassword
                }
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        // Task 27 About section reads versionName/versionCode from BuildConfig.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Required by Robolectric to load Android resources from unit tests.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    // Room schema export: app/schemas/<db>/<version>.json is the migration
    // baseline and is committed to the repository.
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    // Bottom-navigation icons (Home/Search/Settings); version managed by the Compose BOM.
    implementation("androidx.compose.material:material-icons-core")
    // Player controls (Task 21: skip/repeat/shuffle/timer/queue/lyrics icons);
    // extended set is BOM-managed, symbols kept only when referenced.
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    // These catalog aliases are reserved for the source-built Media3 decoder modules.
    // They are not published to Google Maven; Task 17 will wire them via the official
    // AndroidX Media source-build integration without adding FFmpeg.

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.jsoup)
    implementation(libs.tinypinyin)
    // Lexicons' pom references the dead lowercase com.github.promeg:tinypinyin
    // coordinate; the core is supplied above from Maven Central instead.
    implementation(libs.tinypinyin.android.asset.lexicons) {
        exclude(group = "com.github.promeg")
    }
    implementation(libs.juniversalchardet)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented test stack for the device-side FTS5 trigram probe
    // (Task 4: BundledSQLiteDriver + trigram MATCH can only be verified on a
    // real Android runtime — Robolectric/JVM cannot load the bundled native lib).
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(libs.androidx.room.testing)
}
