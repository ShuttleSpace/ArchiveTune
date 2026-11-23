/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.db.entities.SongEntity

/** Writes the synthetic song rows that back custom-source playlist entries. */
@Dao
interface ModuleSongDao {
    @Upsert
    suspend fun upsert(song: SongEntity)

    @Query("SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun songInPlaylist(playlistId: String, songId: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_song_map WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: String): Int

    @Query("SELECT position FROM playlist_song_map WHERE playlistId = :playlistId AND songId = :songId LIMIT 1")
    suspend fun positionOf(playlistId: String, songId: String): Int?

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun deleteMap(playlistId: String, songId: String)

    @Insert
    suspend fun insert(map: PlaylistSongMap)

    /** Source names for the browsable "sources" tree; albumArtist holds the module display name. */
    @Query("SELECT DISTINCT albumArtist FROM song WHERE id LIKE 'lt:%' AND albumArtist IS NOT NULL ORDER BY albumArtist")
    suspend fun moduleSourceNames(): List<String>

    @Query("SELECT * FROM song WHERE id LIKE 'lt:%' AND albumArtist = :source ORDER BY title")
    suspend fun moduleSongsOf(source: String): List<SongEntity>
}
