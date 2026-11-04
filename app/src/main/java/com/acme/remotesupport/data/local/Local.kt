package com.acme.remotesupport.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val deviceCode: String,
    val name: String,
    val model: String?,
    val manufacturer: String?,
    val online: Boolean,
    val broadcasting: Boolean,
    val blocked: Boolean,
    val batteryLevel: Int?,
    val lastScreenshot: String?,
    val lastSeenAt: String?,
    val sortOrder: Int = 0,
)

@Entity(tableName = "pairs")
data class PairEntity(
    @PrimaryKey val id: String,
    val deviceA: String,
    val deviceB: String,
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun byId(id: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<DeviceEntity>)

    @Query("UPDATE devices SET sortOrder = :order WHERE id = :id")
    suspend fun setOrder(id: String, order: Int)

    @Query("DELETE FROM devices WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<String>)

    @Query("DELETE FROM devices")
    suspend fun clear()
}

@Dao
interface PairDao {
    @Query("SELECT * FROM pairs")
    fun observeAll(): Flow<List<PairEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(pairs: List<PairEntity>)

    @Query("DELETE FROM pairs")
    suspend fun clear()
}

@Database(entities = [DeviceEntity::class, PairEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun pairDao(): PairDao
}
