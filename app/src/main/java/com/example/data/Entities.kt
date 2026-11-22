package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object FolderLayouts {
    const val GRID_3X3 = "GRID_3X3"
    const val GRID_2X2 = "GRID_2X2"
    const val ROW_1X2 = "ROW_1X2"
    const val ROW_1X4 = "ROW_1X4"
}

object LargeIconOrientations {
    const val ICON_1X1 = "ICON_1X1"
    const val ICON_1X2 = "ICON_1X2"
    const val ICON_2X1 = "ICON_2X1"
}

object WidgetItemTypes {
    const val FOLDER = "FOLDER"
    const val ICON = "ICON"
}

object FolderBgStyles {
    const val FROSTED_DARK = "FROSTED_DARK"
    const val FROSTED_LIGHT = "FROSTED_LIGHT"
    const val DYNAMIC = "DYNAMIC"
    const val CLEAR = "CLEAR"
}

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val layoutType: String = FolderLayouts.GRID_3X3,
    val bgStyle: String = FolderBgStyles.DYNAMIC,
    val bgAlpha: Float = 0.85f,
    val cornerRadiusDp: Int = 24,
    val showLabels: Boolean = true,
    val showPopupLabels: Boolean = false,
    val isVaultLocked: Boolean = false,
    val pinCode: String = "",
    val sortOrder: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "large_icons")
data class LargeIconEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val appName: String,
    val packageName: String,
    val activityName: String,
    val orientation: String = LargeIconOrientations.ICON_1X1,
    val bgStyle: String = FolderBgStyles.DYNAMIC,
    val cornerRadiusDp: Int = 24,
    val iconPaddingDp: Int = 0,
    val showLabel: Boolean = false,
    val sortOrder: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "folder_apps",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["folderId"]), Index(value = ["packageName"])]
)
data class FolderAppEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderId: Long,
    val packageName: String,
    val activityName: String,
    val appName: String,
    val positionIndex: Int = 0,
    val isHidden: Boolean = false
)

@Entity(tableName = "widget_bindings")
data class WidgetBindingEntity(
    @PrimaryKey
    val appWidgetId: Int,
    val folderId: Long = -1L,
    val lastUpdatedMillis: Long = System.currentTimeMillis(),
    val itemType: String = WidgetItemTypes.FOLDER,
    val largeIconId: Long = -1L
)
