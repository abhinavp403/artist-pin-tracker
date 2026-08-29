import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Read through `providers` rather than File.readText() so the configuration cache
// invalidates when local.properties changes.
fun localProperty(key: String): String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty(key, "") }
    .orElse("")
    .get()

val mapsApiKey: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) }.getProperty("MAPS_API_KEY", "") }
    .orElse("")
    .get()

/** Overridable from local.properties for anyone running their own proxy. */
val DEFAULT_API_BASE_URL = "https://artistpin-proxy-abhinavp403-3191s-projects.vercel.app/api/"

android {
    namespace = "dev.abhinav.artistpin"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.abhinav.artistpin"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        // Places needs the key at runtime, not just in the manifest.
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        // Spotify credentials no longer ship with the app — the proxy in /server holds them.
        // What's left is the proxy's address and an optional key for reaching it, neither of
        // which is worth anything to someone who decompiles the APK.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${localProperty("API_BASE_URL").ifBlank { DEFAULT_API_BASE_URL }}\"",
        )
        buildConfigField("String", "ARTISTPIN_API_KEY", "\"${localProperty("ARTISTPIN_API_KEY")}\"")

        // Supabase. Neither of these is a secret in the way the Spotify credential was: the
        // publishable key is designed to ship in the client, and Row-Level Security is what makes
        // that safe. The service role key bypasses RLS entirely and must never appear here.
        buildConfigField("String", "SUPABASE_URL", "\"${localProperty("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProperty("SUPABASE_ANON_KEY")}\"")
        // Which ConcertRepository implementation is bound. Defaults to Room — the backend is
        // pointless until B7 has imported the existing shows into it, and a build that ships
        // before then would open on an empty map. Flip once the import has run.
        buildConfigField(
            "boolean",
            "USE_BACKEND",
            localProperty("USE_BACKEND").ifBlank { "false" },
        )
        // The *web* OAuth client id, not the Android one — see the note in supabase/README.md.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${localProperty("GOOGLE_WEB_CLIENT_ID")}\"",
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // MigrationTestHelper loads the exported schema JSONs from assets. Scoped to debug so
    // release APKs don't carry them.
    sourceSets.getByName("debug") {
        assets.directories.add("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.maps.compose)
    implementation(libs.maps.compose.utils)
    implementation(libs.places)
    implementation(libs.play.services.location)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.androidx.work.runtime)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.koin.bom))
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
