package dev.abhinav.artistpin.core.di

import androidx.room.Room
import com.google.android.libraries.places.api.Places
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.location.DeviceLocationProvider
import dev.abhinav.artistpin.core.location.PlayServicesLocationProvider
import dev.abhinav.artistpin.core.media.BackupFileStore
import dev.abhinav.artistpin.core.preferences.DataStoreSettingsStore
import dev.abhinav.artistpin.core.preferences.SettingsStore
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.media.MediaUrlResolver
import dev.abhinav.artistpin.BuildConfig
import dev.abhinav.artistpin.core.auth.AuthRepository
import dev.abhinav.artistpin.core.auth.GoogleCredentialClient
import dev.abhinav.artistpin.core.auth.SupabaseAuthRepository
import dev.abhinav.artistpin.data.backend.BackendApi
import dev.abhinav.artistpin.data.backend.SupabaseBackendApi
import dev.abhinav.artistpin.feature.auth.SignInViewModel
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import dev.abhinav.artistpin.data.ArtistImageSource
import dev.abhinav.artistpin.data.BackendBackupRepository
import dev.abhinav.artistpin.data.LibraryBackup
import dev.abhinav.artistpin.data.RoomBackupRepository
import dev.abhinav.artistpin.data.CachingArtistSearch
import dev.abhinav.artistpin.data.ChainedArtistImageSource
import dev.abhinav.artistpin.data.BackendConcertRepository
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.OfflineFirstConcertRepository
import dev.abhinav.artistpin.data.RoomConcertRepository
import dev.abhinav.artistpin.data.sync.LibrarySync
import dev.abhinav.artistpin.data.sync.NoSyncScheduler
import dev.abhinav.artistpin.data.sync.SyncScheduler
import dev.abhinav.artistpin.data.sync.WorkManagerSyncScheduler
import dev.abhinav.artistpin.data.DeezerApi
import dev.abhinav.artistpin.data.DiceEventLinkImporter
import dev.abhinav.artistpin.data.LibraryMigrator
import dev.abhinav.artistpin.data.EventLinkImporter
import dev.abhinav.artistpin.data.DeezerArtistImageSource
import dev.abhinav.artistpin.data.MusicBrainzApi
import dev.abhinav.artistpin.data.MusicBrainzGenreSource
import dev.abhinav.artistpin.data.PlacesVenueSearchService
import dev.abhinav.artistpin.data.RemoteArtistSearch
import dev.abhinav.artistpin.data.SpotifyArtistSearch
import dev.abhinav.artistpin.data.SpotifyApi
import dev.abhinav.artistpin.data.SpotifyArtistImageSource
import dev.abhinav.artistpin.data.VenueSearchService
import dev.abhinav.artistpin.feature.artists.ArtistDetailViewModel
import dev.abhinav.artistpin.feature.artists.ArtistsViewModel
import dev.abhinav.artistpin.feature.eventdetail.EventDetailViewModel
import dev.abhinav.artistpin.feature.eventedit.EventEditViewModel
import dev.abhinav.artistpin.feature.worldmap.WorldMapViewModel
import kotlinx.coroutines.CoroutineDispatcher
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Unknown keys must not fail a parse: Deezer returns far more than we model, and a backup
 * written by a newer version should still load if the schema only grew. */
private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

val dispatcherModule = module {
    single<CoroutineDispatcher>(IoDispatcher) { Dispatchers.IO }
    single<CoroutineDispatcher>(DefaultDispatcher) { Dispatchers.Default }
}

val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            ArtistPinDatabase::class.java,
            ArtistPinDatabase.NAME,
        ).addMigrations(ArtistPinDatabase.MIGRATION_1_2,
            ArtistPinDatabase.MIGRATION_2_3,
            ArtistPinDatabase.MIGRATION_3_4,
            ArtistPinDatabase.MIGRATION_4_5,
            ArtistPinDatabase.MIGRATION_5_6,
            ArtistPinDatabase.MIGRATION_6_7,
            ArtistPinDatabase.MIGRATION_7_8,
            ArtistPinDatabase.MIGRATION_8_9).build()
    }
    single { get<ArtistPinDatabase>().concertDao() }
    single { get<ArtistPinDatabase>().artistDao() }
    single { get<ArtistPinDatabase>().mediaDao() }
    single { get<ArtistPinDatabase>().syncOutboxDao() }
}

val networkModule = module {
    single {
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            // MusicBrainz rejects clients that don't identify themselves.
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build(),
                )
            }
            .build()
    }
    single<OkHttpClient>(ProxyClient) {
        val shared: OkHttpClient = get()
        if (BuildConfig.ARTISTPIN_API_KEY.isBlank()) {
            shared
        } else {
            // newBuilder shares the connection pool and thread pools rather than standing up a
            // second one just to add a header.
            shared.newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("X-ArtistPin-Key", BuildConfig.ARTISTPIN_API_KEY)
                            .build(),
                    )
                }
                .build()
        }
    }
    single<DeezerApi> { retrofit(DEEZER_BASE_URL, get()).create(DeezerApi::class.java) }
    // Spotify is reached through our own proxy, which holds the credentials. Its own client
    // carries the proxy key; the shared one must not, or every Deezer and MusicBrainz request
    // would leak it to a third party.
    single<SpotifyApi> {
        retrofit(BuildConfig.API_BASE_URL, get(ProxyClient)).create(SpotifyApi::class.java)
    }
    single<RemoteArtistSearch> { CachingArtistSearch(SpotifyArtistSearch(get())) }
    single<EventLinkImporter> { DiceEventLinkImporter(get(), json, get(IoDispatcher)) }
    single<MusicBrainzApi> { retrofit(MUSICBRAINZ_BASE_URL, get()).create(MusicBrainzApi::class.java) }
    single<ArtistImageSource> {
        // Spotify supplies the portrait and profile link, MusicBrainz the genres Spotify dropped
        // from its API, and Deezer is the portrait fallback when Spotify has no picture.
        ChainedArtistImageSource(
            listOf(
                SpotifyArtistImageSource(get()),
                MusicBrainzGenreSource(get()),
                DeezerArtistImageSource(get()),
            ),
        )
    }
}

private fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

private const val USER_AGENT = "ArtistPin/1.0 ( https://github.com/artistpin )"
private const val DEEZER_BASE_URL = "https://api.deezer.com/"
private const val MUSICBRAINZ_BASE_URL = "https://musicbrainz.org/"


val dataModule = module {
    single { MediaImporter(androidContext(), get(IoDispatcher)) }
    single { MediaUrlResolver(get(), get(IoDispatcher)) }
    single<DeviceLocationProvider> { PlayServicesLocationProvider(androidContext(), get(IoDispatcher)) }
    single<SettingsStore> { DataStoreSettingsStore(androidContext()) }
    single { BackupFileStore(androidContext(), get(IoDispatcher)) }
    // The Room-backed one is always constructed, not just when Room is bound: the migration has
    // to read the local library in order to upload it, so it needs this specific implementation
    // regardless of which one the UI is talking to.
    single { RoomBackupRepository(get(), get(), get(), get(), json, get(IoDispatcher)) }
    single { LibraryMigrator(get(), get(), json, get(IoDispatcher)) }
    single<LibraryBackup> {
        if (BuildConfig.USE_BACKEND) {
            BackendBackupRepository(get(), json, get(IoDispatcher))
        } else {
            get<RoomBackupRepository>()
        }
    }
    // The swap B6 exists for. Every screen depends on the ConcertRepository interface, so this
    // single line is the whole difference between a device-local app and an account-backed one.
    // Room is constructed either way: with the backend bound it is the offline-first cache the
    // repository reads from and writes through, not a disused alternative.
    single { RoomConcertRepository(get(), get(), get(), get(), get(), get(IoDispatcher)) }
    single<SyncScheduler> {
        // Nothing to sync to on the device-only build, so the scheduler is a no-op rather than a
        // worker that would wake up, find no backend and fail.
        if (BuildConfig.USE_BACKEND) WorkManagerSyncScheduler(androidContext()) else NoSyncScheduler
    }
    single<ConcertRepository> {
        if (BuildConfig.USE_BACKEND) {
            OfflineFirstConcertRepository(get(), get(), get(), json, get())
        } else {
            get<RoomConcertRepository>()
        }
    }
    single { LibrarySync(get(), get(), get(), get(), get(), json, get(IoDispatcher), get()) }
    single<VenueSearchService> {
        val context = androidContext()
        // Resolved lazily per call: Places is only initialized when a key is configured, and
        // createClient throws if it isn't.
        PlacesVenueSearchService(
            clientProvider = { if (Places.isInitialized()) Places.createClient(context) else null },
            ioDispatcher = get(IoDispatcher),
        )
    }
}

val authModule = module {
    single {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth)
            install(Postgrest)
            // Reuses the app's existing OkHttp client, so Supabase shares the connection pool and
            // timeouts rather than standing up a second HTTP stack alongside Retrofit's.
            httpEngine = OkHttp.create { preconfigured = get<OkHttpClient>() }
        }
    }
    // A single scope that outlives every screen: the session has to be observable before any
    // ViewModel exists, because it decides whether the graph containing them is composed at all.
    single<AuthRepository> {
        SupabaseAuthRepository(get(), CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(DefaultDispatcher)))
    }
    single { GoogleCredentialClient(BuildConfig.GOOGLE_WEB_CLIENT_ID) }
    // Declared but not yet consumed: B6 is what points ConcertRepository at it. Wiring it now
    // means the graph verification test covers it before anything depends on it.
    single<BackendApi> { SupabaseBackendApi(get(), get(IoDispatcher)) }
}

val viewModelModule = module {
    viewModelOf(::SignInViewModel)
    viewModelOf(::WorldMapViewModel)
    viewModelOf(::EventDetailViewModel)
    viewModelOf(::EventEditViewModel)
    viewModelOf(::ArtistsViewModel)
    viewModelOf(::ArtistDetailViewModel)
}

val appModules =
    listOf(dispatcherModule, databaseModule, networkModule, authModule, dataModule, viewModelModule)
