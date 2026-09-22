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

// Release signing. The keystore lives outside the repo and its path and passwords come from
// local.properties, which is gitignored — none of it is committable. RELEASE_STORE_FILE is
// resolved against the repository root, so an absolute path is the safest thing to put there.
val releaseStoreFile: String = localProperty("RELEASE_STORE_FILE")

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

    signingConfigs {
        // Only declared when local.properties actually points at a keystore. The alternative —
        // always creating it and letting storeFile be null — fails the build for anyone who
        // clones this without the keystore, including a release build they never asked for.
        if (releaseStoreFile.isNotBlank()) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Null when no keystore is configured, which leaves the APK unsigned rather than
            // silently falling back to the debug key. A debug-signed "release" installs happily
            // and then can never be upgraded by a properly signed one — the signatures differ,
            // so Android rejects it and the only way out is uninstalling and losing local state.
            signingConfig = signingConfigs.findByName("release")

            // R8. The default optimize file plus our own rules below; anything a library needs
            // ships with the library as consumer rules.
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

// Every other way a release build can be misconfigured announces itself: missing Supabase values
// open the config screen, a missing Maps key leaves a grey map, an unsigned APK won't install.
// USE_BACKEND is the exception. False binds RoomConcertRepository, and the result looks entirely
// correct — sign-in works, shows save — while nothing ever syncs and no photo is backed up. It
// also defaults to false, so a fresh checkout produces exactly that build.
val verifyReleaseConfig = tasks.register("verifyReleaseConfig") {
    group = "verification"
    description = "Fails a release build that would never sync, because USE_BACKEND is not true."

    // Read at configuration time and captured as a plain value, so the action holds no reference
    // to the project and the configuration cache stays usable.
    val backendEnabled = localProperty("USE_BACKEND").equals("true", ignoreCase = true)

    doLast {
        check(backendEnabled) {
            "USE_BACKEND is not true in local.properties: this build would keep everything on " +
                "the one device, syncing nothing and backing up no photos."
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseConfig)
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
    implementation(libs.supabase.storage)
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
