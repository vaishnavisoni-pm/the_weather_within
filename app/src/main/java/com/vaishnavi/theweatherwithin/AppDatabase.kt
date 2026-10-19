package com.vaishnavi.theweatherwithin

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CheckInEntity::class,
        AnswerEntity::class,
        FacetEstimateEntity::class,
        DimensionSnapshotEntity::class,
        JournalEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun checkInDao(): CheckInDao

    companion object {

        // Deliberately a new filename, different from the old
        // "weather_within.db". That old file holds real, unencrypted
        // history and is left completely alone, never opened, never
        // deleted. This is a genuine fresh start: Room creates this
        // file from nothing, encrypted from its very first write.
        private const val DB_NAME = "weather_within_secure.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS facet_estimates (
                        dimension TEXT NOT NULL,
                        facet TEXT NOT NULL,
                        estimate REAL NOT NULL,
                        lastUpdated INTEGER NOT NULL,
                        PRIMARY KEY (dimension, facet)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO facet_estimates (dimension, facet, estimate, lastUpdated)
                    SELECT a.dimension, a.facet, AVG(a.load), MAX(c.timestamp)
                    FROM answers a
                    INNER JOIN checkins c ON c.id = a.checkInId
                    GROUP BY a.dimension, a.facet
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dimension_snapshots (
                        dateKey TEXT NOT NULL,
                        dimension TEXT NOT NULL,
                        score INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        PRIMARY KEY (dateKey, dimension)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v3 -> v4: adds journal_entries. New capability, nothing to
         *  backfill — starts capturing from the first entry written
         *  after this migration runs. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        text TEXT NOT NULL,
                        dateKey TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")

            // 32 random bytes from the Keystore-protected store, turned
            // into a 64-character hex string passphrase. The resulting
            // byte array is handed to SupportOpenHelperFactory and MUST
            // NOT be zeroed afterward — Room opens the database lazily
            // and holds a reference to it, so clearing it early would
            // encrypt the database with an all-zero key instead of the
            // real passphrase.
            val keyBytes = DatabasePassphrase.getOrCreate(context)
            val passphrase = keyBytes.joinToString("") { "%02x".format(it) }
            val factory = SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}