plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.thanatophilia.xyuime"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.thanatophilia.xyuime"
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.environmentVariable("XYU_VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.environmentVariable("XYU_VERSION_NAME").orElse("0.1.0-dev").get()
        buildConfigField("String", "GIT_SHA", "\"${providers.environmentVariable("GITHUB_SHA").orElse("local").get().take(8)}\"")
    }
    signingConfigs {
        create("cloud") {
            providers.environmentVariable("XYU_KEYSTORE_PATH").orNull?.let { storeFile = file(it) }
            storeType = "PKCS12"
            storePassword = providers.environmentVariable("XYU_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("XYU_KEY_ALIAS").orElse("xyu-ime").get()
            keyPassword = providers.environmentVariable("XYU_KEYSTORE_PASSWORD").orNull
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("cloud")
            isMinifyEnabled = false
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":rime-engine"))
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
