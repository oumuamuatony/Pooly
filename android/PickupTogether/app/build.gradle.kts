plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val pleosVehicleSdkVersion = "2.3.2"

android {
    namespace = "io.pickuptogether.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.pickuptogether.app"
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 4
        versionName = "0.3.1-dev"
        buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8765\"")
        buildConfigField("String", "GLEO_PACKAGE", "\"ai.umos.ambientai\"")
        buildConfigField("boolean", "DEMO_MODE", "false")
        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"$pleosVehicleSdkVersion\"")
        manifestPlaceholders["appLabel"] = "Pooly"
        manifestPlaceholders["inviteScheme"] = "togethermove"
        manifestPlaceholders["gleoEnabled"] = "true"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    sourceSets["main"].assets.srcDir("../../../together/web")
    sourceSets.create("integration") { manifest.srcFile("src/debug/AndroidManifest.xml"); res.srcDir("src/debug/res") }
    sourceSets.create("demo") { manifest.srcFile("src/debug/AndroidManifest.xml"); res.srcDir("src/debug/res") }
    buildTypes.create("demo") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".demo"
        versionNameSuffix = "-demo"
        matchingFallbacks += listOf("debug")
        buildConfigField("boolean", "DEMO_MODE", "true")
        buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8766\"")
        manifestPlaceholders["appLabel"] = "Pooly 시연"
        manifestPlaceholders["inviteScheme"] = "togetherdemo"
        manifestPlaceholders["gleoEnabled"] = "false"
    }
    buildTypes.create("cloud") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".cloud"
        matchingFallbacks += listOf("debug")
        buildConfigField("String", "API_BASE_URL", "\"https://yeonil.pythonanywhere.com\"")
        manifestPlaceholders["appLabel"] = "Pooly 클라우드"
        manifestPlaceholders["inviteScheme"] = "togethercloud"
        manifestPlaceholders["gleoEnabled"] = "false"
    }
    // A separate identity prevents Android's test runner from removing the user's app.
    buildTypes.create("integration") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".integration"
        matchingFallbacks += listOf("debug")
    }
    testBuildType = providers.gradleProperty("togetherTestBuildType").orElse("integration").get()
}

tasks.matching { it.name == "assembleDemo" }.configureEach {
    doLast {
        copy {
            from(layout.buildDirectory.file("outputs/apk/demo/app-demo.apk"))
            into(rootProject.layout.projectDirectory.dir("../../together/artifacts"))
            rename("app-demo.apk", "together-demo.apk")
        }
    }
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

tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        copy {
            from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
            into(rootProject.layout.projectDirectory.dir("../../together/artifacts"))
            rename("app-debug.apk", "together-dev.apk")
        }
    }
}
