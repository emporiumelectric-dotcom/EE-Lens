package com.fanlens.prototype.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import com.fanlens.prototype.data.db.entity.EmbeddingEntity
import com.fanlens.prototype.data.db.entity.MetaEntity
import com.fanlens.prototype.data.db.entity.PhotoEntity
import com.fanlens.prototype.data.db.entity.ProductEntity

@Database(
    entities = [
        ProductEntity::class,
        PhotoEntity::class,
        EmbeddingEntity::class,
        MetaEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class EeDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao
    abstract fun photoDao(): PhotoDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun metaDao(): MetaDao

    companion object {
        const val NAME = "ee-lens.db"

        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_SEED_VERSION = "seed_version"
        const val KEY_MODEL_VERSION = "model_version"
        const val KEY_LAST_EXPORT_AT = "last_export_at"
        const val KEY_SYNC_ADDRESS = "sync_address"
        const val KEY_SYNC_CODE = "sync_code"

        // Supabase Auth session, for cloud catalogue sync. Never the service_role
        // key -- this is the same email/password session a shop owner signs into,
        // gating writes to the ee_lens tables exactly like the PC tool.
        const val KEY_SUPABASE_ACCESS_TOKEN = "supabase_access_token"
        const val KEY_SUPABASE_REFRESH_TOKEN = "supabase_refresh_token"
        const val KEY_SUPABASE_EXPIRES_AT = "supabase_expires_at"
        const val KEY_SUPABASE_EMAIL = "supabase_email"
        const val KEY_CLOUD_LAST_PUSH_AT = "cloud_last_push_at"
        const val KEY_CLOUD_LAST_PULL_AT = "cloud_last_pull_at"

        /**
         * Adds the photo role. Every existing photo becomes a recognition photo,
         * because that is what all of them have been doing until now — the
         * catalogue keeps working and clean display photos are added on top.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE photos ADD COLUMN role TEXT NOT NULL DEFAULT 'recognition'"
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_photos_role ON photos(role)")
            }
        }

        /**
         * Adds the MRP. Existing prices stay as the selling price, and every
         * product starts with no MRP — which reads as "no discount" rather than
         * inventing one.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE products ADD COLUMN mrp_minor INTEGER")
            }
        }

        /**
         * Adds cloud-sync bookkeeping. Every existing photo starts as never
         * pushed, so the first cloud sync uploads what is already here rather
         * than assuming it is already in Supabase.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE photos ADD COLUMN synced_at INTEGER")
            }
        }

        fun build(context: Context): EeDatabase =
            Room.databaseBuilder(context.applicationContext, EeDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // Cascade deletes are declared on the entities; SQLite ignores them
                // unless foreign keys are switched on for the connection.
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
    }
}
