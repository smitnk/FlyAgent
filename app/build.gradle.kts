plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.smitnk.flyagent"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.smitnk.flyagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
        ndk { abiFilters += "arm64-v8a" } // 64-bit ARM phones; llama.cpp and the connectome need 64-bit
        // Debug APKs default to -O0 native code, which makes llama.cpp 10-50x slower: always build native code optimized
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") } }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    androidResources { noCompress += "bin" }
}
dependencies {
    // on-device, offline image reading (bundled models, no download needed)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in JVM tests
}
