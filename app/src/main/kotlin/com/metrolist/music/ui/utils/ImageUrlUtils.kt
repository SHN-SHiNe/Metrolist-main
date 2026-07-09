/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import timber.log.Timber

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this

    // Support for common Google CDN image sizing parameters.
    val isGoogleCdn = this.contains("googleusercontent.com") || this.contains("ggpht.com")

    if (isGoogleCdn) {
        val w = width ?: height!!
        val h = height ?: width!!

        // Handle wNNN-hNNN pattern often used in path segments
        if (this.contains(Regex("w\\d+-h\\d+"))) {
            return this.replace(Regex("w\\d+-h\\d+"), "w$w-h$h")
        }

        // Find where parameters start. They usually start with =w, =s, or =h
        val baseUrl = this.split("=w", "=s", "=h", limit = 2)[0]

        // If it's a banner (has =w and =h) or if both dimensions were requested, use =w-h-p format for smart cropping
        val result = if ((this.contains("=w") && this.contains("-h")) || (width != null && height != null)) {
            "$baseUrl=w$w-h$h-p-l90-rj"
        } else {
            // Default to =s format for square-ish images
            "$baseUrl=s$w-p-l90-rj"
        }

        Timber.d("Resizing image: $this -> $result")
        return result
    }

    return this
}
