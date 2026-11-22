/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.db.entities

import androidx.room.Entity

/**
 * Last playability check for one playlist entry, so a reopened playlist still shows which
 * songs need replacing instead of re-probing every source on every visit.
 */
@Entity(tableName = "module_sniff", primaryKeys = ["playlistId", "songId"])
data class ModuleSniffEntity(
    val playlistId: String,
    val songId: String,
    /** `no_stream`, `unplayable` or `no_source` — see LocalPlaylistViewModel.SniffState. */
    val status: String,
    val checkedAt: Long,
)
