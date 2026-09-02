// The first migration test in this project — step 4 of the workflow documented at the top of
// SmartPumpMigrations.kt, which had never been carried out (no migration existed to test until v3).
//
// Why this matters more than an ordinary unit test: migrations only ever run against a database
// that already holds a station's real data — its transaction audit log, its identity row, its PIN
// hash. A broken one is discovered in the field, on an app update, with the evidence already
// destroyed. MigrationTestHelper is the only way to exercise that path before shipping, because it
// creates a genuine v2 database on disk and migrates it exactly as an update would.
//
// The v2 fixture is written as literal SQL rather than through Room: after this commit the Kotlin
// entities describe v3, so building the "old" database via the current code is impossible — and a
// fixture derived from today's classes would silently drift with them and stop representing what is
// actually installed on a tablet.
package app.balancee.smartpump.display.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test-db"

@RunWith(AndroidJUnit4::class)
class SmartPumpMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SmartPumpDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * The headline case: a configured v2 pump updates to v3 and keeps its configuration, gaining a
     * NULL fuelType. NULL is the point — the transaction guard reads it as "not configured" and
     * blocks, rather than the migration guessing PETROL and letting a diesel pump authorise against
     * the wrong fuel.
     */
    @Test
    fun migrate2To3_preservesConfig_andAddsNullFuelType() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO device_config (id, pumpId, stationName, koboPerLitre, virtualAccountNumber, updatedAt)
                VALUES (1, 'PUMP 3', 'Total Lekki Ph2', 87050, '0123456789', 1717171717000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *SmartPumpMigrations.ALL)

        db.query("SELECT pumpId, stationName, koboPerLitre, virtualAccountNumber, updatedAt, fuelType FROM device_config WHERE id = 1")
            .use { c ->
                assertTrue("config row survived the migration", c.moveToFirst())
                assertEquals("PUMP 3", c.getString(0))
                assertEquals("Total Lekki Ph2", c.getString(1))
                // Sub-naira price must survive intact — it is the case kobo storage exists for.
                assertEquals(87_050L, c.getLong(2))
                assertEquals("0123456789", c.getString(3))
                assertEquals(1_717_171_717_000L, c.getLong(4))
                assertTrue("fuelType is NULL for a pre-v3 row", c.isNull(5))
            }
    }

    /** An empty v2 database (installed but never configured) must migrate just as cleanly. */
    @Test
    fun migrate2To3_withNoConfigRow_succeeds() {
        helper.createDatabase(TEST_DB, 2).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *SmartPumpMigrations.ALL)

        db.query("SELECT COUNT(*) FROM device_config").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    /**
     * The migration must not touch anything else. The audit log is the station's money record and
     * the identity row holds the PIN hash — an ALTER on one table should leave both alone, but that
     * is the assumption worth pinning, since losing either is unrecoverable and silent.
     */
    @Test
    fun migrate2To3_leavesTransactionsAndIdentityIntact() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO station_identity (id, stationId, displayName, pinHash, pinSalt, setupAtMs)
                VALUES (1, 'STN-001', 'Total Lekki Ph2', 'hash-abc', 'salt-xyz', 1717171717000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *SmartPumpMigrations.ALL)

        db.query("SELECT stationId, pinHash, pinSalt FROM station_identity WHERE id = 1").use { c ->
            assertTrue("identity row survived", c.moveToFirst())
            assertEquals("STN-001", c.getString(0))
            assertEquals("hash-abc", c.getString(1))
            assertEquals("salt-xyz", c.getString(2))
        }
    }

    /**
     * Writing a fuel type through the migrated schema must round-trip. Guards the column being
     * added with a type or default that reads back wrong — the failure that would otherwise surface
     * as a pump forgetting its fuel type after an update.
     */
    @Test
    fun migratedSchema_acceptsAFuelTypeWrite() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO device_config (id, pumpId, stationName, koboPerLitre, virtualAccountNumber, updatedAt)
                VALUES (1, 'PUMP 1', 'Station', 87000, NULL, 1)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *SmartPumpMigrations.ALL)
        db.execSQL("UPDATE device_config SET fuelType = 'DIESEL' WHERE id = 1")

        db.query("SELECT fuelType FROM device_config WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("DIESEL", c.getString(0))
        }

        db.execSQL("UPDATE device_config SET fuelType = NULL WHERE id = 1")
        db.query("SELECT fuelType FROM device_config WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertNull("clearing back to unconfigured is allowed", c.getString(0))
        }
    }
}
