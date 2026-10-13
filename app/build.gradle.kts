import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Spotify developer app settings live in client/local.properties (gitignored):
//   spotify.clientId=…  spotify.redirectUri=…  spotify.clientSecret=… (optional)
// Without them the app builds but shows a "not configured" screen.
// Lyrics: spicylyrics.key=sl_pk_… (a publishable key with "No origin header" allowed). Without it
// the app builds and says lyrics aren't set up.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
fun localProp(key: String) = local.getProperty(key).orEmpty()
// Spotify rejects localhost redirects as insecure ("redirect_uri: Insecure"); use a custom scheme.
val redirectUri = localProp("spotify.redirectUri").ifEmpty { "app.remastered://callback" }
val redirect = URI(redirectUri)
// Only the publishable key may go into the APK. A secret key (sl_sk_…) must stay on a server.
val spicyLyricsKey = localProp("spicylyrics.key").trim()
check(!spicyLyricsKey.startsWith("sl_sk_")) {
    "spicylyrics.key in client/local.properties is a Spicy Lyrics secret key (sl_sk_…). Use a publishable key (sl_pk_…)."
}

android {
    namespace = "app.remastered"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.remastered"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProp("spotify.clientId")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"$redirectUri\"")
        buildConfigField("String", "SPOTIFY_CLIENT_SECRET", "\"${localProp("spotify.clientSecret")}\"")
        buildConfigField("String", "SPICY_LYRICS_KEY", "\"$spicyLyricsKey\"")
        // Spotify's auth AAR registers its callback activity for the redirect URI.
        manifestPlaceholders["redirectSchemeName"] = redirect.scheme
        manifestPlaceholders["redirectHostName"] = redirect.host
        manifestPlaceholders["redirectPathPattern"] = ".*"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Spotify Android SDK (App Remote + Auth), fetched by scripts/fetch-spotify-sdk.sh.
    implementation(fileTree("libs") { include("*.aar") })
    implementation(libs.gson)
    implementation(libs.androidx.browser)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.palette)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
