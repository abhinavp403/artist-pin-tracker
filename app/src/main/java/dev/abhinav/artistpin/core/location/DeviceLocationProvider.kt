package dev.abhinav.artistpin.core.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.abhinav.artistpin.core.model.Coordinates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Where the device is right now, used only to pick the map's opening camera position.
 *
 * Kept behind an interface so the map's start-up behaviour can be tested without Play services,
 * and so a null result — permission denied, location off, no fix — is an ordinary outcome rather
 * than an exception the caller has to handle.
 */
interface DeviceLocationProvider {
    /** Null whenever a position can't be had, for any reason. Never throws. */
    suspend fun currentLocation(): Coordinates?
}

class PlayServicesLocationProvider(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : DeviceLocationProvider {

    // The caller checks the runtime permission before it gets here; a revoked permission still
    // surfaces as a SecurityException below rather than a crash.
    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): Coordinates? = withContext(ioDispatcher) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        runCatching {
            // The cached fix is instant and plenty accurate for "which city am I in". Only when
            // there isn't one — a fresh install, or location just switched on — do we pay for a
            // real fix, and balanced-power is enough to land in the right city.
            client.lastLocation.await()
                ?: client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
        }.getOrNull()?.let { Coordinates(it.latitude, it.longitude) }
    }
}
