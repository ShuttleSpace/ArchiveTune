/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceFolderIdTest {
    @Test
    fun folderIdRoundTripsThroughSourceName() {
        val id = MusicService.sourceFolderId("网易云音乐")
        assertEquals("sources/网易云音乐", id)
        assertEquals("网易云音乐", MusicService.sourceNameOf(id))
    }

    @Test
    fun sourceFolderIsNeverMistakenForTheParentFolder() {
        assertNull(MusicService.sourceNameOf(MusicService.SOURCES))
        assertNull(MusicService.sourceNameOf("recent"))
        assertEquals("", MusicService.sourceNameOf("${MusicService.SOURCES}/"))
    }
}
