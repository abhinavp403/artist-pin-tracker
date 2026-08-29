package dev.abhinav.artistpin.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Asks for the outbox to be drained soon.
 *
 * An interface so the repository can queue work without knowing WorkManager exists — and so tests
 * can assert that a save asked for a sync, without standing up a scheduler at all.
 */
interface SyncScheduler {
    fun requestSync()
}

/** Used when the app is running device-only: there is nothing to sync to. */
object NoSyncScheduler : SyncScheduler {
    override fun requestSync() = Unit
}

/**
 * Drains the queue when the network allows (execution plan C3).
 *
 * The constraint is the whole point. Sync used to run once when the signed-in graph was composed,
 * which meant a show added underground stayed queued until the *next* launch — the phone could
 * regain signal while the app sat open in your hand and nothing would happen. WorkManager holds the
 * request until connectivity exists, then runs it, including after the process has died.
 */
class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {

    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            // Exponential from 30s: a failing backend should not be retried in a tight loop on
            // someone's battery, and the queue is patient by design.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        // KEEP, not REPLACE. Work already waiting on connectivity will drain the *whole* queue
        // when it runs, so a second change needs no second request — and REPLACE would discard the
        // accumulated backoff of a request that has been failing, turning a slow retry into a
        // tight one.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "artistpin-sync"
    }
}

/**
 * Injects through Koin rather than a custom WorkerFactory: WorkManager constructs workers itself,
 * so the dependency has to be pulled in rather than passed in.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val sync: LibrarySync by inject()

    private companion object {
        const val TAG = "ArtistPinSync"
    }

    override suspend fun doWork(): Result = when (val result = sync.syncNow()) {
        is DataResult.Success ->
            // Entries can survive a successful run — the drain stops at the first failure, so a
            // partially-drained queue is a normal outcome and deserves a retry rather than being
            // declared done.
            if (result.data.isFullySynced) Result.success() else Result.retry()

        // Retried either way now. A non-network failure used to end the work outright, which
        // meant that after one bad entry the queue only moved again if some *other* change
        // enqueued fresh work — and that change was stuck behind the same entry. LibrarySync now
        // abandons an entry after MAX_ATTEMPTS, so retrying is bounded rather than endless, and
        // WorkManager's backoff keeps it cheap.
        is DataResult.Failure -> {
            if (result.error !is DataError.Network) {
                Log.w(TAG, "Sync failed for a non-network reason: ${result.error}")
            }
            Result.retry()
        }
    }
}
