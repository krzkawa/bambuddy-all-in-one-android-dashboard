// CI stamps the build it produced into the APK so the phone can say which one
// it is running. A build from a laptop is always "dev".
val buildNumber = (findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
val buildSha = (findProperty("buildSha") as String?).orEmpty().take(7)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.krzkawa.bambuddyaio"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.krzkawa.bambuddyaio"
        // Android 7.0 Nougat — the oldest device this app is built for.
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = if (buildSha.isBlank()) "dev" else "0.1.$buildNumber ($buildSha)"
        resourceConfigurations += listOf("en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so CI can produce an installable APK
            // without a keystore secret. Replace before any store upload.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Lets minSdk 24 use java.time and other newer APIs if we ever need them.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }


    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    lint {
        abortOnError = false
    }

    // `./gradlew :app:testDebugUnitTest -Pshots --tests "*Shots*"` draws every
    // screen to a PNG in build/shots, using the real views and the real fonts.
    // It is the only way to see a change to the way the app looks without an
    // Android phone in your hand, and it is off by default so that neither
    // Robolectric nor its android-all download is anywhere near the build that
    // publishes the APK.
    if (project.hasProperty("shots")) {
        sourceSets.getByName("test").java.srcDir("src/shots/java")
        testOptions {
            unitTests.isIncludeAndroidResources = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Keystore-backed SharedPreferences for the server credential.
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // The android.jar the unit tests run against stubs org.json out; this puts a real
    // implementation on the test classpath so the OpenSpool parsing can be tested.
    testImplementation("org.json:json:20231013")
    if (project.hasProperty("shots")) {
        testImplementation("org.robolectric:robolectric:4.14.1")
        // A stand-in Bambuddy for LiveCheck, which drives the real socket code.
        testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    }
}
