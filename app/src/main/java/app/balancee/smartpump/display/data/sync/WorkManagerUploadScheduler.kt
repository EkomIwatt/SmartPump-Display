// Phase 10f. The WorkManager side of TransactionUploadScheduler.
package app.balancee.smartpump.display.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.balancee.smartpump.display.domain.sync.TransactionUploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : TransactionUploadScheduler {

    override fun requestUpload() {
        val request = OneTimeWorkRequestBuilder<TransactionUploadWorker>()
            // A pump with no internet is the ordinary case at a Nigerian forecourt, not an error.
            // The records wait on disk and go out when the link returns, which is the entire
            // reason this is a durable job rather than a call at the end of a sale.
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            TransactionUploadWorker.WORK_NAME,
            // KEEP, not REPLACE. A burst of sales enqueues a burst of requests, and REPLACE would
            // cancel a run already in flight and reset its backoff each time — so a busy pump on a
            // bad link would restart the queue forever and never finish reporting anything. The
            // job always drains everything pending, so the run already scheduled covers the new
            // record too, and APPEND would serialise a queue of identical jobs for no gain.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        /**
         * Thirty seconds, doubling — WorkManager's own floor is ten, and its ceiling is five hours.
         *
         * The thing being waited on is usually a forecourt's internet coming back or a payment the
         * backend has not confirmed yet (#45's RETRY_LATER), and neither is helped by asking every
         * ten seconds. A dispense that waits an extra minute costs nothing; the record is on disk.
         */
        val BACKOFF: Duration = Duration.ofSeconds(30)
    }
}
