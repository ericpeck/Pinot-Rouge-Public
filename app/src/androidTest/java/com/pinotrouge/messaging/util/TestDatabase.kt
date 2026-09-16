package com.pinotrouge.messaging.util

import android.content.Context
import androidx.room.Room
import com.pinotrouge.messaging.data.room.PinotDatabase

/** In-memory Room for instrumented tests only — never use in production. */
fun createInMemoryDb(context: Context): PinotDatabase {
    return Room.inMemoryDatabaseBuilder(context, PinotDatabase::class.java)
        .allowMainThreadQueries()
        .build()
}
