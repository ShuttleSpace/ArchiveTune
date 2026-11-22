/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import moe.rukamori.archivetune.db.entities.ModuleSniffEntity

@Dao
interface ModuleSniffDao {
    @Query("SELECT * FROM module_sniff WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: String): List<ModuleSniffEntity>

    @Upsert
    suspend fun upsertAll(rows: List<ModuleSniffEntity>)

    @Query("DELETE FROM module_sniff WHERE playlistId = :playlistId")
    suspend fun clear(playlistId: String)

    /** Replaces the whole result set for one playlist in a single transaction. */
    @Transaction
    suspend fun replace(
        playlistId: String,
        rows: List<ModuleSniffEntity>,
    ) {
        clear(playlistId)
        if (rows.isNotEmpty()) upsertAll(rows)
    }
}
