package com.fanlens.prototype.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fanlens.prototype.data.db.entity.MetaEntity

@Dao
interface MetaDao {

    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun value(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: MetaEntity)

    suspend fun put(key: String, value: String) = put(MetaEntity(key, value))

    suspend fun intValue(key: String, fallback: Int = 0): Int =
        value(key)?.toIntOrNull() ?: fallback
}
