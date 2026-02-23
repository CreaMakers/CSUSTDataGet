import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.spider_app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val keystoreProperties = Properties()
            val keystorePropertiesFile = rootProject.file("local.properties")
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }

            val keyStorePath = keystoreProperties.getProperty("release.storeFile")
                ?: System.getenv("RELEASE_STORE_FILE")
                ?: "release-key.jks"

            storeFile = file(keyStorePath)

            storePassword = keystoreProperties.getProperty("release.storePassword")
                ?: System.getenv("RELEASE_STORE_PASSWORD")

            keyAlias = keystoreProperties.getProperty("release.keyAlias")
                ?: System.getenv("RELEASE_KEY_ALIAS")

            keyPassword = keystoreProperties.getProperty("release.keyPassword")
                ?: System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.example.spider_app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":csust_spider"))
    implementation(libs.mmkv)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}