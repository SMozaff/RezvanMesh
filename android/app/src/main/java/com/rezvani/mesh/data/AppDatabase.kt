package com.rezvani.mesh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.rezvani.mesh.data.dao.ChannelDao
import com.rezvani.mesh.data.dao.ContactDao
import com.rezvani.mesh.data.dao.MessageDao
import com.rezvani.mesh.data.entities.ChannelEntity
import com.rezvani.mesh.data.entities.ContactEntity
import com.rezvani.mesh.data.entities.MessageEntity
import net.sqlcipher.database.SupportFactory

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
    version = 1,
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

        private fun buildDatabase(context: Context, passphrase: ByteArray): AppDatabase {
            val factory = SupportFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
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
