// Room-backed implementation of TransactionRepository.
// Writes completed transaction records only — no state or price logic (those are in PulseRepositoryImpl).
package app.balancee.smartpump.display.data.repository

import app.balancee.smartpump.display.data.db.TransactionDao
import app.balancee.smartpump.display.data.db.entities.TransactionEntity
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionMode
import app.balancee.smartpump.display.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val dao: TransactionDao,
) : TransactionRepository {

    override suspend fun saveTransaction(transaction: Transaction) =
        dao.insert(transaction.toEntity())

    override fun getRecentTransactions(limit: Int): Flow<List<Transaction>> =
        dao.getRecent(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun getPendingSync(): List<Transaction> =
        dao.getPendingSync().map { it.toDomain() }

    private fun Transaction.toEntity() = TransactionEntity(
        id = id,
        mode = mode.name,
        paymentMethod = paymentMethod.name,
        litresDispensed = litresDispensed,
        amountKobo = amountKobo,
        priceKoboPerLitre = priceKoboPerLitre,
        transactionRef = transactionRef,
        attendantNote = attendantNote,
        createdAt = createdAt,
        syncedAt = syncedAt,
    )

    private fun TransactionEntity.toDomain() = Transaction(
        id = id,
        mode = TransactionMode.valueOf(mode),
        paymentMethod = PaymentMethod.valueOf(paymentMethod),
        litresDispensed = litresDispensed,
        amountKobo = amountKobo,
        priceKoboPerLitre = priceKoboPerLitre,
        transactionRef = transactionRef,
        attendantNote = attendantNote,
        createdAt = createdAt,
        syncedAt = syncedAt,
    )
}
