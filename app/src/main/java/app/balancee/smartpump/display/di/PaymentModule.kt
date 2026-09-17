// Hilt module — binds PaymentProcessor to the mock implementation.
//
// **Still the mock, on purpose (10c).** `BalanceePaymentProcessor` exists and authorises for real,
// but its terminal result — PAID, by polling — is 10d. Binding it now would hand a customer a QR
// that never resolves. The binding flips in 10d, in one line, with the poll behind it.
package app.balancee.smartpump.display.di

import app.balancee.smartpump.display.data.payment.MockPaymentProcessor
import app.balancee.smartpump.display.data.payment.TransactionIdFactory
import app.balancee.smartpump.display.data.payment.UuidTransactionIdFactory
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

    @Binds @Singleton
    abstract fun bindTransactionIdFactory(impl: UuidTransactionIdFactory): TransactionIdFactory
}
