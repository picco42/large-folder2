package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, id ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllFoldersList(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    fun getFolderByIdFlow(id: Long): Flow<FolderEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity): Long

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun getFolderCount(): Int
}

@Dao
interface FolderAppDao {
    @Query("SELECT * FROM folder_apps WHERE folderId = :folderId ORDER BY positionIndex ASC, id ASC")
    fun getAppsForFolder(folderId: Long): Flow<List<FolderAppEntity>>

    @Query("SELECT * FROM folder_apps WHERE folderId = :folderId ORDER BY positionIndex ASC, id ASC")
    suspend fun getAppsForFolderSync(folderId: Long): List<FolderAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: FolderAppEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<FolderAppEntity>)

    @Query("DELETE FROM folder_apps WHERE folderId = :folderId")
    suspend fun clearAppsForFolder(folderId: Long)

    @Delete
    suspend fun deleteApp(app: FolderAppEntity)

    @Query("DELETE FROM folder_apps WHERE folderId = :folderId AND packageName = :packageName")
    suspend fun deleteAppByPackage(folderId: Long, packageName: String)
}

@Dao
interface LargeIconDao {
    @Query("SELECT * FROM large_icons ORDER BY sortOrder ASC, id ASC")
    fun getAllLargeIcons(): Flow<List<LargeIconEntity>>

    @Query("SELECT * FROM large_icons ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllLargeIconsList(): List<LargeIconEntity>

    @Query("SELECT * FROM large_icons WHERE id = :id LIMIT 1")
    suspend fun getLargeIconById(id: Long): LargeIconEntity?

    @Query("SELECT * FROM large_icons WHERE id = :id LIMIT 1")
    fun getLargeIconByIdFlow(id: Long): Flow<LargeIconEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLargeIcon(largeIcon: LargeIconEntity): Long

    @Update
    suspend fun updateLargeIcon(largeIcon: LargeIconEntity)

    @Delete
    suspend fun deleteLargeIcon(largeIcon: LargeIconEntity)
}

@Dao
interface WidgetBindingDao {
    @Query("SELECT * FROM widget_bindings WHERE appWidgetId = :appWidgetId LIMIT 1")
    suspend fun getBinding(appWidgetId: Int): WidgetBindingEntity?

    @Query("SELECT * FROM widget_bindings")
    suspend fun getAllBindings(): List<WidgetBindingEntity>

    @Query("SELECT * FROM widget_bindings WHERE folderId = :folderId AND itemType = 'FOLDER'")
    suspend fun getBindingsForFolder(folderId: Long): List<WidgetBindingEntity>

    @Query("SELECT * FROM widget_bindings WHERE largeIconId = :largeIconId AND itemType = 'ICON'")
    suspend fun getBindingsForLargeIcon(largeIconId: Long): List<WidgetBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBinding(binding: WidgetBindingEntity)

    @Query("DELETE FROM widget_bindings WHERE appWidgetId = :appWidgetId")
    suspend fun deleteBinding(appWidgetId: Int)
}
