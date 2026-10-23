package com.asawaaah.fil.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FolderEntity::class,
        FeedEntity::class,
        ArticleEntity::class,
        ArticleContentEntity::class,
        KeywordFilterEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class FilDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun contentDao(): ArticleContentDao
    abstract fun filterDao(): KeywordFilterDao

    companion object {
        /** v0.1.2: kind of failure of a source. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN errorKind TEXT")
            }
        }

        /** v0.1.3: sources automatically moved to another site, to be checked. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN movedFrom TEXT")
            }
        }

        /** v0.1.4: article translations made by the on-device model. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE article_content ADD COLUMN aiTranslatedTexts TEXT")
                db.execSQL("ALTER TABLE article_content ADD COLUMN aiTranslatedSourceHash INTEGER")
            }
        }

        fun build(context: Context): FilDatabase =
            Room.databaseBuilder(context, FilDatabase::class.java, "fil.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
