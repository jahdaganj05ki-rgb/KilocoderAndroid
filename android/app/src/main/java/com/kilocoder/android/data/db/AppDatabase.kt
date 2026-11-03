package com.kilocoder.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        ProviderPolicyEntity::class,
        SessionEntity::class,
        SessionMessageEntity::class,
        GitHubConfigEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun policies(): PolicyDao
    abstract fun sessions(): SessionDao
    abstract fun github(): GitHubDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `github_config` (" +
                        "`id` INTEGER NOT NULL, `owner` TEXT NOT NULL, " +
                        "`repo` TEXT NOT NULL, `branch` TEXT NOT NULL, " +
                        "`workflow` TEXT NOT NULL, `tokenCipher` TEXT NOT NULL, " +
                        "`tokenLast4` TEXT NOT NULL, `login` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`providerId` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                        "`pinnedAccountId` TEXT, `summary` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_messages` (" +
                        "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `reasoning` TEXT NOT NULL, " +
                        "`toolCallsJson` TEXT, `toolCallId` TEXT, `toolName` TEXT, " +
                        "`isError` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_messages_sessionId` " +
                        "ON `session_messages` (`sessionId`)",
                )
            }
        }
    }
}
