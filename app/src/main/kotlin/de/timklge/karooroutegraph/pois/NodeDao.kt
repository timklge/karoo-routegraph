package de.timklge.karooroutegraph.pois

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NodeDao {
    @Query("""
        SELECT n.* FROM nodes n
        JOIN tags t ON n.id = t.nodeId
        WHERE n.latitude BETWEEN :minLat AND :maxLat 
        AND n.longitude BETWEEN :minLon AND :maxLon
    """)
    suspend fun getNodesInAreaWithTags(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Map<Node, List<Tag>>

    @Query("""
        SELECT n.*, t.* FROM (
            SELECT * FROM nodes
            WHERE latitude BETWEEN :minLat AND :maxLat
            AND longitude BETWEEN :minLon AND :maxLon
            LIMIT :limit OFFSET :offset
        ) n
        JOIN tags t ON n.id = t.nodeId
    """)
    suspend fun getNodesInAreaWithTagsPaginated(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int, offset: Int): Map<Node, List<Tag>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<Node>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<Tag>)
}