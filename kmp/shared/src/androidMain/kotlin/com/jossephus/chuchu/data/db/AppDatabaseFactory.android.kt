package com.jossephus.chuchu.data.db

import android.content.Context
import androidx.room.Room

@Volatile
private var dbInstance: AppDatabase? = null

private val dbLock = Any()

fun getAppDatabase(context: Context): AppDatabase {
    return dbInstance ?: synchronized(dbLock) {
        dbInstance ?: Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DB_NAME,
        )
            .build()
            .also { dbInstance = it }
    }
}
