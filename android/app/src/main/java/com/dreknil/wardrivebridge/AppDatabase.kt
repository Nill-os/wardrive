package com.dreknil.wardrivebridge

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RunEntity::class, ObservationEntity::class, GnssSatelliteEntity::class, MeshNodeEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): WardriveDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // Adds RunEntity.uploadedAt for the Logs tab's upload-tracking (2026-
        // 09-27) - a plain nullable column add, not a destructive change, so
        // months of real run/observation history on a live device survives
        // the upgrade untouched.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN uploadedAt INTEGER DEFAULT NULL")
            }
        }

        // Adds the four persisted signature-detection flags to observations
        // for the Field Report tab's category counts (2026-09-27) - see
        // ObservationEntity's own comment. Existing rows default to false,
        // which is accurate (never evaluated at capture time), not faked.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE observations ADD COLUMN isTracker INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE observations ADD COLUMN isFlipperZero INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE observations ADD COLUMN isFlockCamera INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE observations ADD COLUMN isSkimmer INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Adds 7 more persisted signature-detection flags (drone/mesh radio/
        // adult toy/glasses/action cam/police cam/pineapple) to observations,
        // same pattern and same "existing rows default to false, honestly
        // meaning never evaluated" reasoning as MIGRATION_2_3 - plus the new
        // gnss_satellites table for the Field Report's GNSS Satellites
        // section (2026-09-28).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE observations ADD COLUMN isDrone INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE observations ADD COLUMN isMeshRadio INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE observations ADD COLUMN isAdultToy INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE observations ADD COLUMN isGlasses INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE observations ADD COLUMN isActionCam INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE observations ADD COLUMN isPoliceCam INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE observations ADD COLUMN isPineapple INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS gnss_satellites (" +
                        "constellation TEXT NOT NULL, svid INTEGER NOT NULL, lastCn0DbHz REAL NOT NULL, " +
                        "usedInFix INTEGER NOT NULL, lastSeenIso TEXT NOT NULL, " +
                        "PRIMARY KEY(constellation, svid))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_gnss_satellites_constellation ON gnss_satellites(constellation)")
            }
        }

        // Adds mesh_nodes: the Meshtastic LoRa nodes the user's own radio reported during a run
        // (2026-10-07, see MeshNodeEntity). A new table only - existing runs are untouched.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mesh_nodes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, runId INTEGER NOT NULL, nodeNum INTEGER NOT NULL, " +
                        "nodeId TEXT NOT NULL, longName TEXT NOT NULL, shortName TEXT NOT NULL, hwModel INTEGER NOT NULL, " +
                        "lat REAL NOT NULL, lon REAL NOT NULL, altitudeM INTEGER NOT NULL, positionTime INTEGER NOT NULL, " +
                        "lastHeard INTEGER NOT NULL, snr REAL NOT NULL, hopsAway INTEGER NOT NULL, viaMqtt INTEGER NOT NULL, " +
                        "heardLat REAL NOT NULL, heardLon REAL NOT NULL, heardAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_mesh_nodes_runId_nodeNum ON mesh_nodes(runId, nodeNum)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "wardrive.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
    }
}
