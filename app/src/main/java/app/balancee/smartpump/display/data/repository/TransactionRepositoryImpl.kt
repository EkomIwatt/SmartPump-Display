// Room-backed implementation of TransactionRepository. Writes completed records only —
// state and price live in PulseRepositoryImpl / DeviceConfigRepositoryImpl respectively.
package app.balancee.smartpump.display.data.repository

import app.balancee.smartpump.display.data.db.TransactionDao
import app.balancee.smartpump.display.data.db.entities.TransactionEntity
import app.balancee.smartpump.display.domain.model.PaymentMethod
import app.balancee.smartpump.display.domain.model.Transaction
import app.balancee.smartpump.display.domain.model.TransactionFlow
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

    override suspend fun getTransaction(id: String): Transaction? =
        dao.getById(id)?.toDomain()

    override fun getRecentTransactions(limit: Int): Flow<List<Transaction>> =
        dao.getRecent(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun getPendingSync(): List<Transaction> =
        dao.getPendingSync().map { it.toDomain() }

    override suspend fun markSynced(id: String, syncedAt: Long) = dao.markSynced(id, syncedAt)

    override suspend fun markUploadFailed(id: String, reason: String) =
        dao.markUploadFailed(id, reason)

    private fun Transaction.toEntity() = TransactionEntity(
        id = id,
        flow = flow.name,
        paymentMethod = paymentMethod?.name,
        litresDispensed = litresDispensed,
        amountKobo = amountKobo,
        priceKoboPerLitre = priceKoboPerLitre,
        transactionRef = transactionRef,
        attendantId = attendantId,
        attendantNote = attendantNote,
        createdAt = createdAt,
        syncedAt = syncedAt,
        recoveredLitres = recoveredLitres,
        paymentReference = paymentReference,
        startedAt = startedAt,
        uploadError = uploadError,
    )

    private fun TransactionEntity.toDomain() = Transaction(
        id = id,
        flow = TransactionFlow.valueOf(flow),
        paymentMethod = paymentMethod?.let { PaymentMethod.valueOf(it) },
        litresDispensed = litresDispensed,
        amountKobo = amountKobo,
        priceKoboPerLitre = priceKoboPerLitre,
        transactionRef = transactionRef,
        attendantId = attendantId,
        attendantNote = attendantNote,
        createdAt = createdAt,
        syncedAt = syncedAt,
        recoveredLitres = recoveredLitres,
        paymentReference = paymentReference,
        startedAt = startedAt,
        uploadError = uploadError,
    )
}
