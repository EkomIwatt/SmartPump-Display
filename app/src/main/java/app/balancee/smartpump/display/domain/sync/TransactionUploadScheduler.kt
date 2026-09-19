// Domain seam for "the backend should hear about this" (Phase 10f).
//
// The ViewModel asks for an upload; it does not know that WorkManager exists, which keeps the one
// class every flow runs through free of Android scheduling and keeps its tests free of Robolectric.
// Same shape as PumpCredentialsStore and DeviceIdProvider: depend on the capability, not the
// mechanism.
package app.balancee.smartpump.display.domain.sync

interface TransactionUploadScheduler {

    /**
     * Ask for the pending dispenses to be reported, as soon as there is a connection.
     *
     * Deliberately *not* a suspending upload the caller waits on. A customer has fuel in their tank
     * by the time this is called and the screen has to move on; the report is the station's
     * bookkeeping, not the customer's transaction. Safe to call when nothing is pending, and safe
     * to call twice — the work is queued under one name.
     */
    fun requestUpload()
}
