package de.timklge.karooroutegraph.pois

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedPbfDao {
    @Query("SELECT * FROM downloaded_pbf")
    fun getAll(): Flow<List<DownloadedPbf>>

    @Query("SELECT * FROM downloaded_pbf WHERE download_status = 'PENDING' ORDER BY countryKey")
    fun getPendingDownloads(): Flow<List<DownloadedPbf>>

    @Query("SELECT * FROM downloaded_pbf WHERE download_status = 'PROCESSING' ORDER BY countryKey")
    fun getProcessingDownloads(): Flow<List<DownloadedPbf>>


    @Query("UPDATE downloaded_pbf SET download_status = :status, progress = :progress WHERE countryKey = :countryKey")
    suspend fun updateDownloadStatus(countryKey: String, status: PbfDownloadStatus, progress: Float)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(downloadedPbf: DownloadedPbf)

    @Query("DELETE FROM downloaded_pbf WHERE countryKey = :countryKey")
    suspend fun delete(countryKey: String)
}