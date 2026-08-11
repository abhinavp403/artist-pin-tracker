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
import dev.abhinav.artistpin.BuildConfig
import dev.abhinav.artistpin.data.ArtistImageSource
import dev.abhinav.artistpin.data.BackupRepository
import dev.abhinav.artistpin.data.CachingArtistSearch
import dev.abhinav.artistpin.data.ChainedArtistImageSource
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.DeezerApi
import dev.abhinav.artistpin.data.DiceEventLinkImporter
import dev.abhinav.artistpin.data.EventLinkImporter
import dev.abhinav.artistpin.data.DeezerArtistImageSource
import dev.abhinav.artistpin.data.MusicBrainzApi
import dev.abhinav.artistpin.data.MusicBrainzGenreSource
import dev.abhinav.artistpin.data.PlacesVenueSearchService
import dev.abhinav.artistpin.data.RemoteArtistSearch
import dev.abhinav.artistpin.data.SpotifyArtistSearch
import dev.abhinav.artistpin.data.SpotifyApi
import dev.abhinav.artistpin.data.SpotifyArtistImageSource
import dev.abhinav.artistpin.data.SpotifyAuthApi
import dev.abhinav.artistpin.data.SpotifyTokenProvider
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
            ArtistPinDatabase.MIGRATION_5_6).build()
    }
    single { get<ArtistPinDatabase>().concertDao() }
    single { get<ArtistPinDatabase>().artistDao() }
    single { get<ArtistPinDatabase>().mediaDao() }
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
    single<DeezerApi> { retrofit(DEEZER_BASE_URL, get()).create(DeezerApi::class.java) }
    single<SpotifyAuthApi> { retrofit(SPOTIFY_ACCOUNTS_URL, get()).create(SpotifyAuthApi::class.java) }
    single<SpotifyApi> { retrofit(SPOTIFY_API_URL, get()).create(SpotifyApi::class.java) }
    single {
        SpotifyTokenProvider(
            authApi = get(),
            clientId = BuildConfig.SPOTIFY_CLIENT_ID,
            clientSecret = BuildConfig.SPOTIFY_CLIENT_SECRET,
        )
    }
    single<RemoteArtistSearch> { CachingArtistSearch(SpotifyArtistSearch(get(), get())) }
    single<EventLinkImporter> { DiceEventLinkImporter(get(), json, get(IoDispatcher)) }
    single<MusicBrainzApi> { retrofit(MUSICBRAINZ_BASE_URL, get()).create(MusicBrainzApi::class.java) }
    single<ArtistImageSource> {
        // Spotify supplies the portrait and profile link, MusicBrainz the genres Spotify dropped
        // from its API, and Deezer is the portrait fallback when Spotify has no picture.
        ChainedArtistImageSource(
            listOf(
                SpotifyArtistImageSource(get(), get()),
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
private const val SPOTIFY_ACCOUNTS_URL = "https://accounts.spotify.com/"
private const val SPOTIFY_API_URL = "https://api.spotify.com/"
private const val MUSICBRAINZ_BASE_URL = "https://musicbrainz.org/"


val dataModule = module {
    single { MediaImporter(androidContext(), get(IoDispatcher)) }
    single<DeviceLocationProvider> { PlayServicesLocationProvider(androidContext(), get(IoDispatcher)) }
    single<SettingsStore> { DataStoreSettingsStore(androidContext()) }
    single { BackupFileStore(androidContext(), get(IoDispatcher)) }
    single { BackupRepository(get(), get(), get(), json, get(IoDispatcher)) }
    single { ConcertRepository(get(), get(), get(), get(), get(), get(IoDispatcher)) }
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

val viewModelModule = module {
    viewModelOf(::WorldMapViewModel)
    viewModelOf(::EventDetailViewModel)
    viewModelOf(::EventEditViewModel)
    viewModelOf(::ArtistsViewModel)
    viewModelOf(::ArtistDetailViewModel)
}

val appModules = listOf(dispatcherModule, databaseModule, networkModule, dataModule, viewModelModule)
