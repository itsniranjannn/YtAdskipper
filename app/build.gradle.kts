plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.njk.adskipper"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.njk.adskipper"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/release.jks")
            storePassword = "adskipper123"
            keyAlias = "adskipper"
            keyPassword = "adskipper123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.transition:transition:1.5.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
