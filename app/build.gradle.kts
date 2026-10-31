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
// Lyrics: optional. The app gets them through the user's warehouse when it serves /api/lyrics (its
// own secret key, ADR 0005). spicylyrics.key=sl_pk_… (a publishable key with "No origin header"
// allowed) adds Spicy Lyrics directly, for when it doesn't. With neither, lyrics aren't set up.
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
// Listening history (music-warehouse): debug builds may start with warehouse.url and
// warehouse.readToken from local.properties. Release builds never embed them; the user sets them
// in Settings.
val warehouseUrl = localProp("warehouse.url").trim()
val warehouseToken = localProp("warehouse.readToken").trim()
// Release signing: release.storeFile=/path/to/release.jks and release.keyAlias=… in
// local.properties, never in the repo. The password comes from REMASTERED_RELEASE_PASSWORD, else
// the macOS Keychain item spotify-remastered-release / keystore. Without a store file (CI, a
// fresh clone) the release build stays unsigned.
val releaseStoreFile = localProp("release.storeFile").trim()
val releaseKeyAlias = localProp("release.keyAlias").trim().ifEmpty { "remastered" }
fun releasePassword(): String =
    System.getenv("REMASTERED_RELEASE_PASSWORD")?.takeIf { it.isNotEmpty() }
        ?: providers.exec {
            commandLine("security", "find-generic-password", "-s", "spotify-remastered-release", "-a", "keystore", "-w")
        }.standardOutput.asText.get().trim()

android {
    namespace = "app.remastered"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.remastered"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"

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

    signingConfigs {
        if (releaseStoreFile.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releasePassword()
                keyAlias = releaseKeyAlias
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "WAREHOUSE_URL", "\"$warehouseUrl\"")
            buildConfigField("String", "WAREHOUSE_TOKEN", "\"$warehouseToken\"")
        }
        release {
            buildConfigField("String", "WAREHOUSE_URL", "\"\"")
            buildConfigField("String", "WAREHOUSE_TOKEN", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Release code (R8, not debuggable) signed with the debug key, so it installs over the
        // debug build on the phone without an uninstall: for judging performance, which debug
        // builds of Compose understate badly. `./gradlew :app:installProfile`.
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
