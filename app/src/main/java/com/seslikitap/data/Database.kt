package com.seslikitap.data

import android.content.Context
import androidx.room.Dao
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

    @Query("UPDATE books SET finished = :finished WHERE id = :id")
    suspend fun setFinished(id: Long, finished: Boolean)
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

@Database(
    entities = [BookEntity::class, FolderEntity::class, PositionEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun folders(): FolderDao
    abstract fun positions(): PositionDao
    abstract fun bookmarks(): BookmarkDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "seslikitap.db").build()
    }
}
