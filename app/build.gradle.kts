import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.nodedroidrun"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        val stream = FileInputStream(localPropertiesFile)
        localProperties.load(stream)
        stream.close()
    }
    val githubClientId = localProperties.getProperty("github.client.id") ?: ""
    val githubClientSecret = localProperties.getProperty("github.client.secret") ?: ""


    defaultConfig {
        applicationId = "com.example.nodedroidrun"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")
        buildConfigField("String", "GITHUB_CLIENT_SECRET", "\"$githubClientSecret\"")

        ndk {
            // arm64-v8a = celulares físicos modernos
            // x86_64    = emulador Android Studio
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // Não comprime as libs nativas — necessário para que o Android
            // extraia o libnode.so para um local executável no dispositivo
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }


    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}