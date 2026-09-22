// Phase 10f. Binds the upload scheduler.
//
// Unconditional, unlike PaymentModule's MOCK_PAYMENTS switch: the worker uploads whatever the
// records say, and on a mock-payment build there are no records with a paymentReference to upload,
// so the queue is simply empty. A second implementation would be a second thing to keep in step
// with no behaviour to show for it.
package app.balancee.smartpump.display.di

import app.balancee.smartpump.display.data.sync.WorkManagerUploadScheduler
import app.balancee.smartpump.display.domain.sync.TransactionUploadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindUploadScheduler(impl: WorkManagerUploadScheduler): TransactionUploadScheduler
}
