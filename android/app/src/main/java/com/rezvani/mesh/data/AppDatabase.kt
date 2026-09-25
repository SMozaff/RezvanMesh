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
import java.io.File

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
    version = 3,
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
         * Adds the per-channel sender key to `channels`.
         *
         * The shared sender key previously existed only in the native engine's
         * in-memory map, so a joined channel's row outlived its ability to send
         * or receive: after a restart the channel still appeared in the UI and
         * was silently dead. Storing the key beside the membership flag makes
         * "joined" and "can decrypt" the same fact, and lets the service
         * re-install keys into the engine on start.
         *
         * Nullable with no backfill, deliberately. Existing rows describe
         * channels the user has not been given a key for, and inventing one
         * would produce a key that disagrees with every other member's -- the
         * worst possible value, since it fails as "messages are encrypted
         * wrong" rather than "you are not a member". A null key is the honest
         * state and the join-with-key flow fills it in.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN senderKey BLOB")
            }
        }

        private val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
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
         * backup, we move the old encrypted file aside and start fresh rather
         * than crash the app -- but we flag it via [wasWiped] so the UI can
         * show the user a one-time "local history was reset for a security fix"
         * notice instead of silently discarding their messages.
         *
         * The old file is *renamed*, not deleted, so it remains on disk under a
         * `.undecryptable-<timestamp>` name. Nothing in the app can read it
         * (that is the whole point), but it is not destroyed either, which
         * matters more than it sounds: the passphrase it was written with is
         * gone, so the data is unrecoverable by us -- but if a future bug means
         * we wiped for the *wrong* reason, an operator can still get the file
         * off the device.
         */
        @Volatile
        var wasWiped: Boolean = false
            private set

        /**
         * Substrings that identify "this file is not decryptable with the key we
         * have" as opposed to "something else went wrong".
         *
         * SQLCipher surfaces a wrong key as SQLite reports a header it does not
         * recognise, so the message text is the only signal available. Matching
         * on it is unpleasant, but matching on the *exception type* alone is
         * not enough: a failed migration, a missing table, and a genuine
         * SQLCipher error can all arrive as the same class.
         */
        private val UNDECRYPTABLE_MARKERS = listOf(
            "file is not a database",
            "file is encrypted or is not a database",
            "unsupported file format",
            "database disk image is malformed"
        )

        /**
         * Open the database, and wipe it only if it genuinely cannot be
         * decrypted with the supplied passphrase.
         *
         * This previously caught `Exception` and deleted the database file
         * unconditionally. That made a routine bug anywhere on the path to
         * `open()` -- a bad migration, a SQL typo in a DAO query, a missing
         * table, an I/O error while the device is out of space -- silently
         * destroy every stored message, and then still throw if the rebuild
         * failed for the same underlying reason. The user saw their history
         * vanish with no explanation and no way back.
         *
         * Now the file is only discarded when the evidence says the problem is
         * the key, and even then it is *renamed* rather than deleted so the
         * data is still recoverable off-device.
         */
        private fun openOrRecreate(context: Context, passphrase: ByteArray): AppDatabase {
            return try {
                buildDatabase(context, passphrase)
            } catch (e: Exception) {
                if (!isUndecryptable(e)) throw e

                val appContext = context.applicationContext
                val dbFile = appContext.getDatabasePath(DATABASE_NAME)
                if (!dbFile.exists()) throw e

                // Move aside instead of deleting. `deleteDatabase` also removes
                // the -wal and -shm sidecars; renaming only the main file would
                // leave a stale WAL that SQLite could try to replay, so all
                // three go.
                val stamp = System.currentTimeMillis()
                for (suffix in listOf("", "-wal", "-shm")) {
                    val f = File(dbFile.path + suffix)
                    if (f.exists()) {
                        val renamed = File(f.parentFile, "${f.name}.undecryptable-$stamp")
                        if (!f.renameTo(renamed)) {
                            // Could not preserve it; fall back to a real delete so
                            // we do not leave a file we cannot open in place.
                            appContext.deleteDatabase(DATABASE_NAME)
                            break
                        }
                    }
                }
                wasWiped = true
                buildDatabase(context, passphrase)
            }
        }

        /**
         * Whether this throwable indicates the database file cannot be read
         * with the current passphrase.
         *
         * Walks the cause chain because SQLCipher/Room wrap the underlying
         * SQLite error, so the useful message is usually not on the outermost
         * exception.
         */
        private fun isUndecryptable(t: Throwable): Boolean {
            var current: Throwable? = t
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                val message = current.message?.lowercase().orEmpty()
                if (UNDECRYPTABLE_MARKERS.any { message.contains(it) }) return true
                current = current.cause
                depth++
            }
            return false
        }

        private const val MAX_CAUSE_DEPTH = 16

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
                .addMigrations(*ALL_MIGRATIONS)
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
