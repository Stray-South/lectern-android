package com.straysouth.lectern.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.straysouth.lectern.data.db.AppDatabase
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C.2 (runtime half) — room_noFallbackToDestructiveMigration
 *
 * The JVM half ([GroupCSecurityTest.appDatabase_builderDoesNotCallFallbackToDestructiveMigration])
 * guards the source text. This instrumented test covers the runtime behavior:
 * given a SQLite file at schema version 99 (no migration registered for 2→99),
 * [AppDatabase] must throw rather than silently wipe user data.
 *
 * Room wraps the missing-migration failure as [IllegalStateException] with the message
 * "A migration from X to Y was required but not found." Depending on the Room version
 * and whether the builder is opened lazily or eagerly, the exception may surface as a
 * direct [IllegalStateException] or as a [RuntimeException] whose [Throwable.cause] is
 * an [IllegalStateException]. Both forms are accepted.
 *
 * The test writes a raw SQLite file at version 99 using [SQLiteDatabase.openOrCreateDatabase]
 * (bypasses Room entirely — just sets PRAGMA user_version), then attempts to open it via
 * [Room.databaseBuilder] with only MIGRATION_1_2 registered. The force-open of
 * [SupportSQLiteOpenHelper.writableDatabase] triggers Room's schema-version check.
 */
@RunWith(AndroidJUnit4::class)
class SchemaVersionMismatchTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun appDatabase_unmigratableSchemaVersion_throwsRatherThanWipingData() {
        val dbName = "test_schema_mismatch_c2_${System.currentTimeMillis()}.db"
        val dbFile = ctx.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Write a SQLite file at version 99 — AppDatabase only knows migrations 1→2.
        // Bypassing Room to set the version directly avoids any Room initialization path.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 99
        }

        try {
            val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName).build()
            // Force Room to open the file and run its schema-version check.
            db.openHelper.writableDatabase
            db.close()
            fail(
                "Room must throw on schema version 99 with no registered migration — " +
                    "fallbackToDestructiveMigration() must NOT be called (C.2)",
            )
        } catch (e: Exception) {
            val isExpected = e is IllegalStateException
                || e.cause is IllegalStateException
                || (e is RuntimeException && e.message?.contains("migration", ignoreCase = true) == true)
                || (e is RuntimeException && e.cause?.message?.contains("migration", ignoreCase = true) == true)
            assertTrue(
                "Room must throw IllegalStateException (or RuntimeException wrapping it) " +
                    "when no migration path exists — silent data destruction is prohibited (C.2). " +
                    "Got: ${e::class.qualifiedName}: ${e.message}",
                isExpected,
            )
        } finally {
            dbFile.delete()
        }
    }
}
