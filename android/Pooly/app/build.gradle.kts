plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val pleosVehicleSdkVersion = "2.3.2"

android {
    namespace = "io.pooly.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.pooly.app"
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 5
        versionName = "1.0.0"
        // The app talks only to the deployed Pooly Flask server.
        buildConfigField("String", "API_BASE_URL", "\"https://yeonil.pythonanywhere.com\"")
        buildConfigField("String", "GLEO_PACKAGE", "\"ai.umos.ambientai\"")
        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"$pleosVehicleSdkVersion\"")
        manifestPlaceholders["appLabel"] = "Pooly"
        manifestPlaceholders["inviteScheme"] = "pooly"
        manifestPlaceholders["gleoEnabled"] = "true"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    sourceSets["main"].assets.srcDir("../../../pooly/web")
    // Instrumented tests install under a separate identity so the user's Pooly app and session stay intact.
    buildTypes.create("integration") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".integration"
        matchingFallbacks += listOf("debug")
        manifestPlaceholders["appLabel"] = "Pooly 테스트"
        manifestPlaceholders["inviteScheme"] = "poolytest"
        manifestPlaceholders["gleoEnabled"] = "false"
    }
    testBuildType = "integration"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
    implementation("ai.pleos.playground:Vehicle:$pleosVehicleSdkVersion")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.15.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    compileOnly(files("${android.sdkDirectory}/platforms/android-34/optional/android.car.jar"))
}

// Export the installable APK to pooly/artifacts/pooly.apk.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        copy {
            from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
            into(rootProject.layout.projectDirectory.dir("../../pooly/artifacts"))
            rename("app-debug.apk", "pooly.apk")
        }
    }
}
