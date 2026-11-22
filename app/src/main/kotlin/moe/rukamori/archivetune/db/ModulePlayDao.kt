package moe.rukamori.archivetune.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.db.entities.ModulePlayEntity

@Dao
interface ModulePlayDao {
    @Upsert
    suspend fun record(entity: ModulePlayEntity)

    @Query("SELECT * FROM module_play ORDER BY playedAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<ModulePlayEntity>>
}
