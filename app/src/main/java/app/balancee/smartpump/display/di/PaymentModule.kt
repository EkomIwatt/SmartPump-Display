// Hilt module — picks the payment processor per build type.
//
// **Phase 10d bound the real one.** Until the poll existed, `BalanceePaymentProcessor` could
// authorise but never resolve, so binding it would have handed a customer a QR that hung forever;
// `PaymentModule` bound the mock unconditionally and said so. The poll and the resume path landed
// together, and this now branches on `BuildConfig.MOCK_PAYMENTS`.
//
// The branch mirrors `HardwareModule`'s `MOCK_HARDWARE`, for the same reason and in the same shape:
// `debug` and `debugRealHw` point at the dev backend, where no pump has ever been activated — the
// real processor would refuse every sale there, and the debug screen's auto-approve / force-resolve
// controls only exist on the mock. `debugProd` and `release` point at production, where the
// activated pump lives, and take real payments.
//
// `@Provides` with a `Provider` rather than `@Binds`, so only the selected implementation is ever
// constructed — the unselected one never opens a socket or touches the network stack.
package app.balancee.smartpump.display.di

import app.balancee.smartpump.display.BuildConfig
import app.balancee.smartpump.display.data.payment.BalanceePaymentProcessor
import app.balancee.smartpump.display.data.payment.MockPaymentProcessor
import app.balancee.smartpump.display.data.payment.TransactionIdFactory
import app.balancee.smartpump.display.data.payment.UuidTransactionIdFactory
import app.balancee.smartpump.display.domain.payment.PaymentProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PaymentModule {

    @Provides
    @Singleton
    fun providePaymentProcessor(
        mock: Provider<MockPaymentProcessor>,
        real: Provider<BalanceePaymentProcessor>,
    ): PaymentProcessor = if (BuildConfig.MOCK_PAYMENTS) mock.get() else real.get()

    @Provides
    @Singleton
    fun provideTransactionIdFactory(impl: UuidTransactionIdFactory): TransactionIdFactory = impl
}
