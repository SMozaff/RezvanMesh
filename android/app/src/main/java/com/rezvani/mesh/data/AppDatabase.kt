package com.rezvani.mesh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rezvani.mesh.data.dao.ChannelDao
import com.rezvani.mesh.data.dao.ContactDao
import com.rezvani.mesh.data.dao.MessageDao
import com.rezvani.mesh.data.entities.ChannelEntity
import com.rezvani.mesh.data.entities.ContactEntity
import com.rezvani.mesh.data.entities.MessageEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Main Room database for Rezvan Mesh.
 * Encrypted using SQLCipher with a passphrase derived from Android Keystore.
 */
@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        ChannelEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun channelDao(): ChannelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DATABASE_NAME = "rezvan_mesh.db"
        private const val KEY_ALIAS = "rezvan_db_key"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN protocolMessageId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN recipientNodeId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN remoteReceivedAtMs INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN remoteAckSenderId TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_senderId_protocolMessageId " +
                        "ON messages(senderId, protocolMessageId)"
                )
            }
        }

        /**
         * Gets the database instance.
         *
         * @param context Application context.
         * @param passphrase Database encryption passphrase (derived from Keystore).
         */
        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = openOrRecreate(context, passphrase)
                INSTANCE = db
                db
            }
        }

        /**
         * Opens the database with the given (now per-install, Keystore-backed)
         * passphrase. Pre-fix beta builds all shared one hardcoded passphrase,
         * so an existing on-disk database from before this fix will not open
         * with the new random key. Since this is pre-1.0 beta with no server
         * backup, we wipe the old encrypted file and start fresh rather than
         * crash the app -- but we flag it via [wasWiped] so the UI can show
         * the user a one-time "local history was reset for a security fix"
         * notice instead of silently discarding their messages.
         */
        @Volatile
        var wasWiped: Boolean = false
            private set

        private fun openOrRecreate(context: Context, passphrase: ByteArray): AppDatabase {
            return try {
                buildDatabase(context, passphrase)
            } catch (e: Exception) {
                val dbFile = context.applicationContext.getDatabasePath(DATABASE_NAME)
                if (dbFile.exists()) {
                    context.applicationContext.deleteDatabase(DATABASE_NAME)
                    wasWiped = true
                }
                buildDatabase(context, passphrase)
            }
        }

        /**
         * Whether `System.loadLibrary("sqlcipher")` has been called yet.
         * Bug fix: the sqlcipher-android 4.x artifact (migrated to from the
         * deprecated android-database-sqlcipher package -- see the
         * dependency comment in app/build.gradle.kts) does NOT auto-load
         * its native library the way the old package did via
         * SQLiteDatabase.loadLibs(). Per Zetetic's own docs and the
         * library's README, `System.loadLibrary("sqlcipher")` must be
         * called explicitly before ANY database operation -- and nothing
         * in this codebase was doing that, so every attempt to open the
         * database (Contacts, Messages, Chat Detail -- anything going
         * through AppDatabase) crashed with:
         *   UnsatisfiedLinkError: No implementation found for long
         *   net.zetetic.database.sqlcipher.SQLiteConnection.nativeOpen(...)
         * The crash surfaces asynchronously on a background thread (Room's
         * connection pool opens lazily on first real use, not inside
         * `.build()` itself), which is why it appeared as an unhandled
         * exception on tapping into Contacts/Messages rather than at
         * service startup.
         */
        @Volatile
        private var sqlCipherLoaded = false

        @Synchronized
        private fun ensureSqlCipherLoaded() {
            if (sqlCipherLoaded) return
            System.loadLibrary("sqlcipher")
            sqlCipherLoaded = true
        }

        private fun buildDatabase(context: Context, passphrase: ByteArray): AppDatabase {
            ensureSqlCipherLoaded()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                .build()
        }

        /**
         * Closes and clears the database instance (for testing or reset).
         */
        fun destroyInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
