plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val googleWebClientId = providers.environmentVariable("GOOGLE_WEB_CLIENT_ID").orElse("").get().replace("\\r", "").replace("\\n", "").trim()
val ciStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val ciStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val ciKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val ciKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

android {
    namespace = "com.newstar.live"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"" + googleWebClientId.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        applicationId = "com.newstar.live"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
    }
    signingConfigs {
        if (ciStoreFile != null && ciStorePassword != null && ciKeyAlias != null && ciKeyPassword != null) {
            create("ci") {
                storeFile = file(ciStoreFile)
                storePassword = ciStorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
        if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (ciStoreFile != null && ciStorePassword != null && ciKeyAlias != null && ciKeyPassword != null) signingConfig = signingConfigs.getByName("ci")
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
}
