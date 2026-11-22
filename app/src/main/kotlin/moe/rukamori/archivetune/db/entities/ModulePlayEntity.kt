package moe.rukamori.archivetune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One playback of a track that came from a custom source (`.arch` module). */
@Entity(tableName = "module_play")
data class ModulePlayEntity(
    @PrimaryKey val mediaId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val moduleId: String,
    val playedAt: Long,
)
