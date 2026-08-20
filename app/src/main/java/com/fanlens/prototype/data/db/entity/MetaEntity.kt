package com.fanlens.prototype.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Small key/value settings table: seed version, last export date, and similar. */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String
)
