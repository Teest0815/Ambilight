plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Die CI übergibt bei jedem Build eine neue Nummer (-PappVersionCode, -PappVersionName),
// lokal gilt der Standard.
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 2
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.3.0-dev"

android {
    namespace = "com.teest.ambilight"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.teest.ambilight"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Fester Schlüssel, damit neue Versionen über die alte installiert werden können.
    // Er liegt NICHT im Repository: Die CI schreibt ihn aus dem GitHub-Secret KEYSTORE_BASE64 nach
    // app/release.jks und liest das Passwort aus KEY_PASSWORD. Ohne Schlüssel (z. B. lokal oder in
    // einem Fork) wird mit dem Android-Debug-Schlüssel signiert.
    val keystore = file("release.jks")
    val signPw = System.getenv("KEY_PASSWORD")
    val haveKey = keystore.exists() && !signPw.isNullOrEmpty()
    if (haveKey) {
        signingConfigs {
            create("release") {
                storeFile = keystore
                storePassword = signPw
                keyAlias = "ambilight"
                keyPassword = signPw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (haveKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (haveKey) signingConfig = signingConfigs.getByName("release")
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
