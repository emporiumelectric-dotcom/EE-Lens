import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// A release built on one PC must install as an *update* to a release built on
// another, or the phone rejects it and demands an uninstall first. That only
// works if every PC signs with the same key -- so this is deliberately one
// shared file (keystore.properties + keystore/), copied between machines out
// of band, never generated fresh per machine the way debug keys are.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseSigning) load(keystorePropertiesFile.inputStream())
}

// The base, hand-set version -- bump these for a real milestone. The
// automated release workflow (.github/workflows/android-build.yml) instead
// passes -PoverrideVersionCode/-PoverrideVersionName so every automated build
// gets a genuinely higher version than the last one without editing this file
// on every merge -- see UpdateChecker.isNewer, which compares exactly the
// versionName ending up in BuildConfig. A plain local `./gradlew
// assembleDebug`/`assembleRelease` passes neither override, so it keeps
// building this version exactly as written below.
val overrideVersionCode = (findProperty("overrideVersionCode") as String?)?.toIntOrNull()
val overrideVersionName = findProperty("overrideVersionName") as String?

android {
    namespace = "com.fanlens.prototype"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fanlens.prototype"
        minSdk = 26
        targetSdk = 37
        versionCode = overrideVersionCode ?: 14
        versionName = overrideVersionName ?: "0.10.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Without keystore.properties present (a PC it hasn't been copied
            // to yet), this still compiles but is left unsigned -- Android
            // refuses to install an unsigned APK, so a missing key fails
            // loudly at install time rather than silently shipping a build
            // nobody else's phone will accept as an update.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // Exported catalogue files record which app version wrote them.
        buildConfig = true
    }

    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

// Room writes the generated schema here so future migrations can be diffed and
// tested against a known-good baseline instead of being written from memory.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    val room = "2.8.4"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // Thumbnail loading with memory and disk caching for product lists.
    // The network fetcher is deliberately not included: every image is local.
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraX = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    // Do not exclude com.google.android.datatransport from this dependency.
    // ImageEmbedder.createFromOptions constructs a RemoteLoggingClient
    // unconditionally, so removing the classes fails with NoClassDefFoundError
    // and recognition never starts. The uploader is instead made inert by
    // stripping the INTERNET permission in AndroidManifest.xml.
    implementation("com.google.mediapipe:tasks-vision:1.0.0")

    // On-device (not cloud) text recognition -- reads printed/embossed brand
    // text off a real product so BrandTextConflict can veto a confident but
    // wrong shape match (see that file's own doc comment for the real case
    // this exists for). Play-Services-backed: the small Latin-script model
    // downloads once via Play Services the first time it's used, then runs
    // fully offline -- this app already declares INTERNET (for cloud sync),
    // so that first-run download is not a new requirement in practice.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, so .eelens parsing is tested rather than stubbed out.
    testImplementation("org.json:json:20240303")
    // The real SQLite engine Room compiles PhotoEntity's schema to on-device
    // (constraints included), so PhotoPullOrderingTest exercises the actual
    // unique-index enforcement pullProduct hits, not an assumption about it.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
