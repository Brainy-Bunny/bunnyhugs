import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing config, read from credentials/keystore.properties (gitignored - see .gitignore's
// `credentials/` entry - so the actual store/key passwords never enter version control). That file
// intentionally does not exist on a fresh checkout of this repo (e.g. CI, a new dev machine); in that
// case the release signingConfig below is simply left unset, so `assembleRelease` still produces a
// build (just unsigned / debug-signed by AGP's own fallback) rather than failing the whole build.
val keystorePropertiesFile = rootProject.file("credentials/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.ssbmedia.twogether"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ssbmedia.twogether"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "3.1"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file("credentials/${keystoreProperties.getProperty("storeFile")}")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Feature 4 (weekly local backup): plain WorkManager, no other new dependencies needed - the backup
    // format itself is written with java.util.zip + org.json, both already part of the Android platform.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Gallery photo backfill: reads EXIF DateTimeOriginal off a picked photo to pre-fill its date.
    // PickVisualMedia itself needs no new dependency - it's already part of activity-compose 1.9.2 above.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // Item 5 (deferred UX fix, 4-model advisory audit): optional biometric unlock on the PIN screen.
    // androidx.biometric 1.1.0 is the standard stable release. BiometricPrompt's constructor in this
    // version requires a FragmentActivity (or Fragment) host - there is no ComponentActivity overload
    // until the still-unreleased-stable 1.2.0-alpha line - so MainActivity was switched from
    // ComponentActivity to FragmentActivity (a strict superset: FragmentActivity extends ComponentActivity,
    // so every existing activity-compose/registerForActivityResult API MainActivity already used keeps
    // working unchanged). fragment-ktx is declared explicitly (rather than relying on it transitively via
    // biometric) because AndroidX artifacts commonly declare their own dependencies as `implementation`,
    // not `api`, in their Gradle module metadata - which would make FragmentActivity resolve at runtime
    // but fail to even COMPILE against from this module without an explicit dependency of our own.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // ultimate-app-review spec-tests: unit tests here need real org.json (Android's unit-test stub
    // jar throws "not mocked" for it) and Mockito to construct GattSyncManager without a real
    // Context/Room DB - see app/src/test/.../audit/'s own doc for what this suite covers and why.
    testImplementation("org.json:json:20231013")
    testImplementation("org.mockito:mockito-core:5.12.0")
}
