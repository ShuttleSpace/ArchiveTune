/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import moe.rukamori.archivetune.BuildConfig

/**
 * Fork feature: the automotive flavour gets a denser, larger-target variant of a few
 * screens. Car head units are short (typically 1080x600) and are used at a glance, so
 * rows trade a second subtitle line for height and buttons grow to 48dp.
 */
object CarMode {
    val enabled: Boolean get() = BuildConfig.DEVICE == "automotive"
}
