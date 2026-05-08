// Hilt module — binds PaymentProcessor to the mock implementation.
// Replace with the real Balanceè-backed processor in a later phase.
package app.balancee.smartpump.display.di

import app.balancee.smartpump.display.data.payment.MockPaymentProcessor
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PaymentModule {

    @Binds @Singleton
    abstract fun bindPaymentProcessor(impl: MockPaymentProcessor): PaymentProcessor
}
