plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "com.dcelysia.csust_spider"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
}

dependencies {
    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    //Retrofit
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.converter.scalars)
    // jsoup
    implementation(libs.jsoup)
    //MMKV
    implementation(libs.mmkv)
    // Gson
    implementation(libs.gson)
    implementation(libs.mmkv)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
publishing{
    publications{
        register<MavenPublication>("release"){
            groupId = "com.declysia.csust"
            artifactId = "spider"
            version = "1.0.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}