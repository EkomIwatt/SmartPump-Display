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

object SmartPumpMigrations {

    /** All migrations, in order. Empty until the first post-baseline schema change (v2 → v3). */
    val ALL: Array<Migration> = arrayOf(
        // MIGRATION_2_3,
    )
}
