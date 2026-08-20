package com.fanlens.prototype.data

import android.content.Context
import com.fanlens.prototype.data.db.EeDatabase

/**
 * One database and one repository for the whole process.
 *
 * The activity is recreated on rotation; the catalogue is not. Phase 2 replaces
 * the call sites with ViewModels, but the single instance stays here.
 */
object EeGraph {

    @Volatile
    private var database: EeDatabase? = null

    @Volatile
    private var repository: CatalogRepository? = null

    fun database(context: Context): EeDatabase =
        database ?: synchronized(this) {
            database ?: EeDatabase.build(context).also { database = it }
        }

    fun repository(context: Context): CatalogRepository =
        repository ?: synchronized(this) {
            repository ?: CatalogRepository(
                context = context.applicationContext,
                database = database(context),
                photoStore = PhotoStore(context)
            ).also { repository = it }
        }
}
