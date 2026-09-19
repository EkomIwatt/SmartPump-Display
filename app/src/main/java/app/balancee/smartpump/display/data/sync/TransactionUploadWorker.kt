// Phase 10f. The Android half of the upload job, and deliberately almost nothing.
//
// Every decision worth arguing about lives in TransactionUploader, which has no Android in it and
// is tested on the JVM. This class exists to answer one question WorkManager asks — run, and then
// should I come back? — and to be the thing that survives a reboot, because a forecourt tablet
// loses power and the records it is holding must not need someone to reopen the app.
package app.balancee.smartpump.display.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TransactionUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: TransactionUploader,
) : CoroutineWorker(context, params) {

    /**
     * **There is no `Result.failure()` branch, and that is the design.**
     *
     * `failure()` means "give up on this work for good", and the only thing that may abandon a
     * dispense is the uploader itself — by marking the row, with a reason, in the log a person
     * reads. A worker that failed would drop the record silently and leave the row looking pending
     * forever, which is the outcome this whole phase exists to prevent.
     *
     * An unexpected throw is the same argument: the safe answer is to come back, not to conclude
     * anything about the records.
     */
    override suspend fun doWork(): Result = try {
        when (uploader.uploadPending()) {
            UploadRun.SETTLED -> Result.success()
            UploadRun.RETRY -> Result.retry()
        }
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Upload run threw; asking to be run again", t)
        Result.retry()
    }

    companion object {
        private const val TAG = "TxnUpload"

        /** The unique-work name. One queue, so a burst of sales cannot start a burst of workers. */
        const val WORK_NAME = "transaction-upload"
    }
}
