plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.fanlens.prototype"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fanlens.prototype"
        minSdk = 26
        targetSdk = 37
        versionCode = 14
        versionName = "0.10.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testImplementation("junit:junit:4.13.2")
    // The real org.json, so .eelens parsing is tested rather than stubbed out.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
