package dev.abhinav.artistpin.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The handful of choices that should outlive a launch — currently just how the artist list is
 * ordered.
 *
 * Behind an interface so screens can be tested without a real file on disk, and so this stays the
 * only place that knows preferences are stored at all.
 */
interface SettingsStore {
    /** Null until something has been chosen, and whenever the stored value isn't recognized. */
    val artistSort: Flow<String?>

    suspend fun setArtistSort(value: String)
}

private val Context.preferences: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreSettingsStore(private val context: Context) : SettingsStore {

    override val artistSort: Flow<String?> = context.preferences.data
        // A corrupt or unreadable preferences file must not take the artist list down with it:
        // an unusable setting is the same as an unset one.
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it[ARTIST_SORT] }

    override suspend fun setArtistSort(value: String) {
        context.preferences.edit { it[ARTIST_SORT] = value }
    }

    private companion object {
        val ARTIST_SORT = stringPreferencesKey("artist_sort")
    }
}
