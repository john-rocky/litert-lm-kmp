plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "${providers.gradleProperty("litertlm.namespace").get()}.sample"
    // Compose 1.12 requires 37 to compile against.
    compileSdk = 37

    defaultConfig {
        applicationId = namespace
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = providers.gradleProperty("litertlm.version").get()
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // So a release build can be installed for checking. A real app signs with its own key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":sample-shared"))
    implementation(libs.compose.runtime)
    implementation(libs.androidx.activity.compose)
}
