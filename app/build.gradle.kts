import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * The debug keystore is committed (base64) so that every debug APK — built locally or on
 * any CI runner — is signed with the same key and installs over the previous build
 * instead of failing with INSTALL_FAILED_UPDATE_INCOMPATIBLE. See DECISIONS.md.
 *
 * Decoded by a *task* rather than at configure time: configure-time decoding writes the
 * file before `clean` runs, so `./gradlew clean assembleDebug` in one invocation deletes
 * it again and fails at `validateSigningDebug`. Wiring it to `preBuild` means it is always
 * recreated after a clean, and the up-to-date check keeps it free on repeat builds.
 */
val debugKeystoreSource = rootProject.file("keystore/debug.keystore.base64")
val debugKeystoreFile: File = layout.buildDirectory.file("keystore/debug.keystore").get().asFile

val prepareDebugKeystore = tasks.register("prepareDebugKeystore") {
    description = "Decodes the committed debug keystore so debug builds are stably signed."
    onlyIf { debugKeystoreSource.exists() }
    inputs.file(debugKeystoreSource).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(debugKeystoreFile)
    doLast {
        debugKeystoreFile.parentFile.mkdirs()
        debugKeystoreFile.writeBytes(
            Base64.getDecoder().decode(debugKeystoreSource.readText().filterNot { it.isWhitespace() }),
        )
    }
}

tasks.named("preBuild") { dependsOn(prepareDebugKeystore) }

android {
    namespace = "com.galleryorganizer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.galleryorganizer"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.11.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The target device is a Galaxy S25 Ultra and the APK is installed by hand from a
        // CI artifact. ML Kit's bundled models ship native libraries for four ABIs, which
        // makes a universal debug APK about 150 MB — most of it for architectures this
        // phone will never run. Restricting to arm64 cuts that by roughly two thirds.
        // See DECISIONS.md.
        ndk {
            abiFilters += "arm64-v8a"
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
            arg("room.generateKotlin", "true")
        }
    }

    signingConfigs {
        getByName("debug") {
            if (debugKeystoreSource.exists()) {
                // The file itself may not exist yet at configure time; prepareDebugKeystore
                // creates it before anything needs to sign with it.
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release signing config: this app is never submitted to the Play Store.
            // Debug builds are the shipping vehicle. See DECISIONS.md.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
            )
        }
    }

    // Room's exported schema JSON must be visible to JVM unit tests so migration tests
    // can build a database at an older version.
    sourceSets.getByName("test") {
        resources.srcDir("$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.paging.common)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.androidx.work.testing)
}
