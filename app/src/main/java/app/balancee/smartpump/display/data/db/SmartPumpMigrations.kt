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
     * (API_CONFORMANCE_AUDIT.md §6 #4), so an operator sets it on the device. Added as a
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

    /** All migrations, in order. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_2_3,
    )
}
