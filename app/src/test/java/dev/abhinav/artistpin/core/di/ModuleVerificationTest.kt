package dev.abhinav.artistpin.core.di

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dev.abhinav.artistpin.core.database.ArtistDao
import dev.abhinav.artistpin.core.database.ConcertDao
import dev.abhinav.artistpin.core.auth.AuthRepository
import dev.abhinav.artistpin.core.auth.GoogleCredentialClient
import dev.abhinav.artistpin.core.database.MediaDao
import dev.abhinav.artistpin.core.location.DeviceLocationProvider
import dev.abhinav.artistpin.core.preferences.SettingsStore
import dev.abhinav.artistpin.data.ArtistImageSource
import dev.abhinav.artistpin.core.media.BackupFileStore
import dev.abhinav.artistpin.data.RoomBackupRepository
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.EventLinkImporter
import dev.abhinav.artistpin.data.LibraryBackup
import dev.abhinav.artistpin.data.LibraryMigrator
import dev.abhinav.artistpin.data.backend.BackendApi
import dev.abhinav.artistpin.data.RemoteArtistSearch
import dev.abhinav.artistpin.data.VenueSearchService
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.test.verify.verify
import org.robolectric.RobolectricTestRunner

/**
 * Koin resolves the graph at runtime, so without this a missing binding would only surface as a
 * crash on whichever screen needed it. Verification turns that into a failing build instead.
 *
 * Each module is checked against the types supplied by the modules it sits on top of, plus the
 * types the framework injects at call time (Context, SavedStateHandle).
 */
@RunWith(RobolectricTestRunner::class)
class ModuleVerificationTest {

    @Test
    fun `every dependency in the graph is declared`() {
        dispatcherModule.verify()

        databaseModule.verify(extraTypes = listOf(Context::class))

        networkModule.verify()

        dataModule.verify(
            extraTypes = listOf(
                Context::class,
                CoroutineDispatcher::class,
                ConcertDao::class,
                ArtistDao::class,
                MediaDao::class,
                ArtistImageSource::class,
                BackendApi::class,
                kotlinx.serialization.json.Json::class,
            ),
        )

        authModule.verify(
            extraTypes = listOf(OkHttpClient::class, CoroutineDispatcher::class),
        )

        viewModelModule.verify(
            extraTypes = listOf(
                SavedStateHandle::class,
                AuthRepository::class,
                GoogleCredentialClient::class,
                ConcertRepository::class,
                VenueSearchService::class,
                RemoteArtistSearch::class,
                EventLinkImporter::class,
                RoomBackupRepository::class,
                LibraryBackup::class,
                BackupFileStore::class,
                LibraryMigrator::class,
                DeviceLocationProvider::class,
                SettingsStore::class,
            ),
        )
    }
}
