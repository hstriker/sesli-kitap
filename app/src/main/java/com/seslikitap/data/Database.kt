package com.seslikitap.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

object BookStatus {
    const val OK = 0
    const val ERROR = 1
    const val DRM = 2
}

@Entity(
    tableName = "books",
    indices = [Index(value = ["uri"], unique = true), Index("folderUri")],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val folderUri: String,
    val fileName: String,
    /** Seçilen klasöre göre alt klasör yolu, ör. "Romanlar/Klasikler/" */
    val relativePath: String,
    val title: String,
    val author: String,
    val language: String?,
    val sizeBytes: Long,
    val lastModified: Long,
    val coverPath: String?,
    val chapterCount: Int,
    val status: Int,
    val errorMessage: String?,
    val searchKey: String,
    val parserVersion: Int,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    /** 0..1 arası okuma ilerlemesi (seslendirme aşamasında dolacak). */
    val progress: Float = 0f,
    val finished: Boolean = false,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    /** Bu kitabı toplam dinleme süresi (ms). */
    @ColumnInfo(defaultValue = "0") val listenedMs: Long = 0,
    val series: String? = null,
    val seriesIndex: Double? = null,
    /** Kitabın bitirildiği zaman (yıllık hedef için). */
    val finishedAt: Long? = null,
)

@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "shelf_books", primaryKeys = ["shelfId", "bookId"], indices = [Index("bookId")])
data class ShelfBookEntity(
    val shelfId: Long,
    val bookId: Long,
    val addedAt: Long,
)

@Entity(tableName = "quotes", indices = [Index("bookId")])
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val blockIndex: Int,
    val sentenceIndex: Int,
    val text: String,
    val bookTitle: String,
    val author: String,
    val createdAt: Long,
)

/** Günlük dinleme süresi. day: "2026-11-15" */
@Entity(tableName = "listening")
data class ListeningDay(
    @PrimaryKey val day: String,
    val ms: Long,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val addedAt: Long,
)

/** Kitapta kalınan yer. */
@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey val bookId: Long,
    val chapterIndex: Int,
    val blockIndex: Int,
    val sentenceIndex: Int,
    val updatedAt: Long,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val blockIndex: Int,
    val sentenceIndex: Int,
    val excerpt: String,
    val note: String?,
    val createdAt: Long,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE folderUri = :folderUri")
    suspend fun forFolder(folderUri: String): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(books: List<BookEntity>)

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM books WHERE folderUri = :folderUri")
    suspend fun deleteFolder(folderUri: String)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun markOpened(id: Long, time: Long)

    @Query("UPDATE books SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float)

    @Query("UPDATE books SET finished = :finished, finishedAt = CASE WHEN :finished THEN :time ELSE NULL END WHERE id = :id")
    suspend fun setFinished(id: Long, finished: Boolean, time: Long)

    /** Serideki sonraki kitap (aynı seri, daha büyük sıra numarası). */
    @Query(
        "SELECT * FROM books WHERE series = :series AND seriesIndex > :index AND status = 0 " +
            "ORDER BY seriesIndex LIMIT 1"
    )
    suspend fun nextInSeries(series: String, index: Double): BookEntity?

    @Query("UPDATE books SET coverPath = :coverPath, chapterCount = :chapters WHERE id = :id")
    suspend fun setCoverAndChapters(id: Long, coverPath: String?, chapters: Int)

    @Query("UPDATE books SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE books SET listenedMs = listenedMs + :ms WHERE id = :id")
    suspend fun addListened(id: Long, ms: Long)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE uri = :uri")
    suspend fun delete(uri: String)
}

@Dao
interface PositionDao {
    @Query("SELECT * FROM positions WHERE bookId = :bookId")
    suspend fun get(bookId: Long): PositionEntity?

    @Upsert
    suspend fun upsert(position: PositionEntity)

    @Query("DELETE FROM positions WHERE bookId IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, blockIndex, sentenceIndex")
    fun observe(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId IN (:ids)")
    suspend fun deleteForBooks(ids: List<Long>)
}

@Dao
interface ShelfDao {
    @Query("SELECT * FROM shelves ORDER BY name")
    fun observeShelves(): Flow<List<ShelfEntity>>

    @Insert
    suspend fun insert(shelf: ShelfEntity): Long

    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteShelf(id: Long)

    @Query("DELETE FROM shelf_books WHERE shelfId = :id")
    suspend fun clearShelf(id: Long)

    @Query("SELECT * FROM shelf_books")
    fun observeLinks(): Flow<List<ShelfBookEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(link: ShelfBookEntity)

    @Query("DELETE FROM shelf_books WHERE shelfId = :shelfId AND bookId = :bookId")
    suspend fun remove(shelfId: Long, bookId: Long)

    @Query("SELECT shelfId FROM shelf_books WHERE bookId = :bookId")
    fun observeShelvesOf(bookId: Long): Flow<List<Long>>
}

@Dao
interface QuoteDao {
    @Query("SELECT * FROM quotes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<QuoteEntity>>

    @Insert
    suspend fun insert(quote: QuoteEntity): Long

    @Query("DELETE FROM quotes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ListeningDao {
    @Query("INSERT OR IGNORE INTO listening(day, ms) VALUES(:day, 0)")
    suspend fun ensure(day: String)

    @Query("UPDATE listening SET ms = ms + :ms WHERE day = :day")
    suspend fun add(day: String, ms: Long)

    @Query("SELECT * FROM listening ORDER BY day")
    fun observeAll(): Flow<List<ListeningDay>>
}

@Database(
    entities = [
        BookEntity::class, FolderEntity::class, PositionEntity::class, BookmarkEntity::class,
        ListeningDay::class, ShelfEntity::class, ShelfBookEntity::class, QuoteEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun folders(): FolderDao
    abstract fun positions(): PositionDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun listening(): ListeningDao
    abstract fun shelves(): ShelfDao
    abstract fun quotes(): QuoteDao

    companion object {
        /** 1 → 2: favoriler, kitap başına dinleme süresi, günlük dinleme tablosu. Mevcut veriler korunur. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN listenedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `listening` (`day` TEXT NOT NULL, `ms` INTEGER NOT NULL, PRIMARY KEY(`day`))"
                )
            }
        }

        /** 2 → 3: seri, bitirme tarihi, raflar, alıntılar. Mevcut veriler korunur. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN series TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN seriesIndex REAL")
                db.execSQL("ALTER TABLE books ADD COLUMN finishedAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shelves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shelf_books` (`shelfId` INTEGER NOT NULL, `bookId` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`shelfId`, `bookId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_shelf_books_bookId` ON `shelf_books` (`bookId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `quotes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `blockIndex` INTEGER NOT NULL, " +
                        "`sentenceIndex` INTEGER NOT NULL, `text` TEXT NOT NULL, `bookTitle` TEXT NOT NULL, " +
                        "`author` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quotes_bookId` ON `quotes` (`bookId`)")
                // Daha önce bitmiş kitaplara bitirme tarihi olarak son açılma zamanını ver
                db.execSQL("UPDATE books SET finishedAt = lastOpenedAt WHERE finished = 1")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "seslikitap.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
