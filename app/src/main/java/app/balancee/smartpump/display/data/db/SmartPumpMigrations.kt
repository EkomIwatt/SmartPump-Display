// Room migrations for SmartPumpDatabase.
//
// Schema JSONs are exported to app/schemas (exportSchema = true + the room.schemaLocation
// KSP arg) and committed to git. Version 2 is the baseline — it predates schema export, so
// there is no 1.json and no 1→2 migration (nothing shipped at v1; the old destructive
// fallback simply rebuilt the DB). From v3 onward every schema change must ship a real
// migration here so a station's transaction audit log / identity / PIN hash is never wiped
// on an app update.
//
// Workflow to bump the schema:
//   1. Change the entity, bump @Database(version = N) in SmartPumpDatabase.
//   2. Build → Room writes app/schemas/<db>/N.json. Diff it against (N-1).json.
//   3. Add MIGRATION_(N-1)_N below with the ALTER/CREATE SQL, append it to ALL.
//   4. Add a MigrationTestHelper test (androidTest) that migrates (N-1) → N and validates.
//
// Release builds carry no destructive fallback (see DatabaseModule): a missing migration
// throws loudly instead of dropping tables.
package app.balancee.smartpump.display.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object SmartPumpMigrations {

    /**
     * v2 → v3 (Phase 7b): adds `device_config.fuelType`.
     *
     * `/authorise` requires a fuel type and nothing in the Pump API supplies one
     * (docs/journal/closed/API_CONFORMANCE_AUDIT.md §6 #4), so an operator sets it on the device. Added as a
     * **nullable** column with no default: existing rows migrate to NULL, which the transaction
     * guard treats as "not configured" and blocks on. Back-filling a guess — PETROL, say — would
     * silently authorise a diesel pump against the wrong fuel, so NULL is the honest state.
     *
     * Only an ADDed column, so no table rebuild: the audit log, identity row and PIN hash are
     * untouched.
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE device_config ADD COLUMN fuelType TEXT DEFAULT NULL")
        }
    }

    /**
     * v3 -> v4 (Phase 7h): pulse-gap reconciliation.
     *
     * Three additions, one migration, because they only mean anything together: the anchor that
     * makes a gap measurable, the place an unattributable gap is recorded, and the field that lets
     * an affected sale explain itself. See OPEN_QUESTIONS #25.
     *
     * 1. `pulse_state.adapterCount` — the adapter's free-running count at the last write.
     *    Added NULLABLE with no default. Zero is a real adapter reading (a board that just booted),
     *    so back-filling zero would tell the reconciler that a pre-update pump had an anchor of
     *    zero and invite it to attribute the adapter's whole lifetime count as one gap. NULL means
     *    "no anchor recorded", which the reconciler refuses to guess from. Same reasoning as the
     *    nullable fuelType at v3: the honest state beats a plausible-looking guess.
     *
     * 2. `transactions.recoveredLitres` — NOT NULL DEFAULT 0. The opposite call to (1), and for a
     *    reason: "no recovery was applied to this sale" is simply TRUE of every row written before
     *    this phase existed, so zero is a fact rather than a guess.
     *
     * 3. `events` — new table, created empty. Typed via a `type` column rather than being
     *    pulse-gap-specific, so `PWR-03` power events land here later without another migration.
     *
     * Two ADDed columns and a CREATE, so no table rebuild: the audit log, identity row and PIN
     * hash are untouched.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pulse_state ADD COLUMN adapterCount INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE transactions ADD COLUMN recoveredLitres REAL NOT NULL DEFAULT 0.0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `type` TEXT NOT NULL,
                    `createdAtMs` INTEGER NOT NULL,
                    `transactionRef` TEXT,
                    `pulses` INTEGER,
                    `pulsesPerLitre` REAL,
                    `detail` TEXT,
                    `syncedAt` INTEGER
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * v4 -> v5 (Phase 10f): the three columns a dispense upload needs, and the one that records
     * why it never happened.
     *
     * All three NULLABLE with no default, and for the same reason each time: there is no honest
     * value to back-fill. A sale that completed before this existed has no `BPM-…` reference on
     * file, did not record when fuel started flowing, and has not failed to upload — it was never
     * offered to the upload job at all. NULL says that; a default would invent history.
     *
     * 1. `transactions.paymentReference` — the server's own reference, from `/authorise`.
     *    `POST /transactions/upload` **requires** it and only `/authorise` issues one, so a row
     *    without it can never be uploaded. That is correct for a cash sale, which nothing
     *    authorised, and it is what `getPendingSync` filters on so cash never enters the queue.
     *
     * 2. `transactions.startedAt` — epoch millis when fuel began to flow, for the upload's
     *    `startedAt`. Distinct from `createdAt`, which is when the sale *completed*.
     *
     * 3. `transactions.uploadError` — why this row will never be uploaded, set only for a failure
     *    the #45 taxonomy calls TERMINAL. The row stays in the log, unsynced and carrying its
     *    reason, rather than being retried forever or quietly marked done.
     *
     * Three ADDed columns, so no table rebuild: every existing audit row survives untouched, which
     * is the whole point of migrating rather than falling back destructively.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN paymentReference TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE transactions ADD COLUMN startedAt INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE transactions ADD COLUMN uploadError TEXT DEFAULT NULL")
        }
    }

    /** All migrations, in order. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
    )
}
