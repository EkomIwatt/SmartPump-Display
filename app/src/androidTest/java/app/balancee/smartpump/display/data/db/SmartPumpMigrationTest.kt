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

    // ---- v3 -> v4 (Phase 7h: pulse-gap reconciliation) --------------------------------------

    /**
     * The headline case, and the one the whole migration exists for: the anchor arrives as NULL,
     * not 0. Zero is a real adapter reading (a board that just booted), so a back-filled zero
     * would tell the reconciler that a pump updating from v3 had an anchor of zero and invite it
     * to attribute the adapter's entire lifetime count as one enormous gap — billing a customer
     * for every litre the pump has ever sold. NULL is the honest "no anchor recorded".
     */
    @Test
    fun migrate3To4_addsNullAdapterAnchor_notZero() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO pulse_state (id, transactionStateJson, currentTransactionRef, pulseCount, lastPulseTimeMs, updatedAt)
                VALUES (1, '{"type":"Idle"}', 'BLC-00847', 417, 1717171717000, 1717171717000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *SmartPumpMigrations.ALL)

        db.query("SELECT pulseCount, currentTransactionRef, adapterCount FROM pulse_state WHERE id = 1")
            .use { c ->
                assertTrue("recovery row survived the migration", c.moveToFirst())
                assertEquals(417, c.getInt(0))
                assertEquals("BLC-00847", c.getString(1))
                assertTrue("adapterCount is NULL for a pre-v4 row, never 0", c.isNull(2))
            }
    }

    /**
     * The opposite call, deliberately: recoveredLitres is NOT NULL DEFAULT 0. For a sale written
     * before this phase existed, "no recovery was applied" is simply true, so zero is a fact
     * rather than a guess. The money columns beside it must come through untouched.
     */
    @Test
    fun migrate3To4_addsZeroRecoveredLitres_andKeepsTheSale() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO transactions (id, flow, paymentMethod, litresDispensed, amountKobo, priceKoboPerLitre, transactionRef, attendantId, attendantNote, createdAt, syncedAt)
                VALUES ('txn-1', 'FIXED_PREPAY_DIGITAL', 'BANK_QR_TRANSFER', 10.0, 870500, 87050, 'BLC-847', NULL, NULL, 1717171717000, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *SmartPumpMigrations.ALL)

        db.query("SELECT litresDispensed, amountKobo, priceKoboPerLitre, recoveredLitres FROM transactions WHERE id = 'txn-1'")
            .use { c ->
                assertTrue("audit row survived", c.moveToFirst())
                assertEquals(10.0, c.getDouble(0), 0.0001)
                assertEquals(870_500L, c.getLong(1))
                assertEquals(87_050L, c.getLong(2))
                assertEquals("no recovery was applied to a pre-7h sale", 0.0, c.getDouble(3), 0.0001)
            }
    }

    /** The events table is created empty and accepts a row with the nullable columns unset. */
    @Test
    fun migrate3To4_createsEventsTable_thatAcceptsAWrite() {
        helper.createDatabase(TEST_DB, 3).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *SmartPumpMigrations.ALL)

        db.query("SELECT COUNT(*) FROM events").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("events starts empty", 0, c.getInt(0))
        }

        db.execSQL(
            """
            INSERT INTO events (type, createdAtMs, transactionRef, pulses, pulsesPerLitre, detail, syncedAt)
            VALUES ('PULSE_GAP_UNEXPLAINED', 1717171717000, NULL, 150, 100.0, 'adapter restarted', NULL)
            """.trimIndent(),
        )

        db.query("SELECT id, type, pulses, pulsesPerLitre, transactionRef, syncedAt FROM events")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("id autoincrements", c.getLong(0) > 0)
                assertEquals("PULSE_GAP_UNEXPLAINED", c.getString(1))
                assertEquals(150, c.getInt(2))
                // The K-factor is stored beside the raw count, so the entry stays interpretable
                // after calibration changes it (OPEN_QUESTIONS #1).
                assertEquals(100.0, c.getDouble(3), 0.0001)
                assertTrue("a gap with no transaction in flight has no ref", c.isNull(4))
                assertNull("unsynced", c.getString(5))
            }
    }

    /**
     * A v2 pump that skipped v3 entirely — the tablet that sat in a box through one release — must
     * walk both migrations in one update and arrive intact. This is the case a per-step test never
     * covers, and the one where a broken chain destroys a station's records silently.
     */
    @Test
    fun migrate2To4_chainsBothMigrations_preservingEverything() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO device_config (id, pumpId, stationName, koboPerLitre, virtualAccountNumber, updatedAt)
                VALUES (1, 'PUMP 3', 'Total Lekki Ph2', 87050, '0123456789', 1717171717000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO station_identity (id, stationId, displayName, pinHash, pinSalt, setupAtMs)
                VALUES (1, 'STN-001', 'Total Lekki Ph2', 'hash-abc', 'salt-xyz', 1717171717000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, *SmartPumpMigrations.ALL)

        db.query("SELECT koboPerLitre, fuelType FROM device_config WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(87_050L, c.getLong(0))
            assertTrue("v3's nullable fuelType still reads NULL after v4", c.isNull(1))
        }
        db.query("SELECT pinHash, pinSalt FROM station_identity WHERE id = 1").use { c ->
            assertTrue("PIN survived two migrations", c.moveToFirst())
            assertEquals("hash-abc", c.getString(0))
            assertEquals("salt-xyz", c.getString(1))
        }
        db.query("SELECT COUNT(*) FROM events").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }
}
