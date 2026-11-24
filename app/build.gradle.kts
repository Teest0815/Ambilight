plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.teest.ambilight"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.teest.ambilight"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Fester Schlüssel, damit neue Versionen über die alte installiert werden können.
    // Der Schlüssel liegt im (privaten) Repository. Wird das Repo öffentlich, bitte einen neuen
    // Schlüssel erzeugen und die Passwörter als GitHub-Secrets hinterlegen.
    signingConfigs {
        create("release") {
            storeFile = file("ambilight-release.jks")
            storePassword = "ambilight-release"
            keyAlias = "ambilight"
            keyPassword = "ambilight-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
