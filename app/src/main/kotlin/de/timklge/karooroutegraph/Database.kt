package de.timklge.karooroutegraph

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.timklge.karooroutegraph.pois.DownloadedPbf
import de.timklge.karooroutegraph.pois.DownloadedPbfDao
import de.timklge.karooroutegraph.pois.Node
import de.timklge.karooroutegraph.pois.Tag

@Database(entities = [DownloadedPbf::class, Node::class, Tag::class], version = 1)
abstract class RoutegraphDatabase : RoomDatabase() {
    abstract fun downloadedPbfDao(): DownloadedPbfDao
}

class RoutegraphDatabaseProvider(val applicationContext: Context) {
    val db = Room.databaseBuilder(applicationContext,
        RoutegraphDatabase::class.java, "routegraph"
    ).build()
}