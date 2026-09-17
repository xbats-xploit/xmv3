package com.xmxbats.music

/**
 * Jembatan in-memory antara:
 *  - JS di WebView (lewat window.AndroidBridge.updateMeta/updateState) -> update data lagu
 *  - Tombol notifikasi/lockscreen -> balik manggil TP()/NX()/PV() di JS lewat onNativeAction
 *
 * Kenapa perlu ini: notifikasi HARUS di-post oleh proses app ini sendiri (bukan Chrome)
 * supaya ikonnya jadi ikon app, bukan ikon Chrome.
 */
object MediaBridge {
    @Volatile var title: String = "XM Xbats Music"
    @Volatile var artist: String = ""
    @Volatile var artwork: String = ""
    @Volatile var isPlaying: Boolean = false
    @Volatile var position: Double = 0.0
    @Volatile var duration: Double = 0.0

    // Dipanggil oleh MainActivity/JsBridge tiap ada perubahan dari web -> beri tahu service
    var onWebStateChanged: (() -> Unit)? = null

    // Dipanggil oleh MediaNotificationService/MediaSession saat user pencet tombol di
    // notifikasi/lockscreen -> MainActivity meneruskan ini ke JS (TP/NX/PV/seek)
    var onNativeAction: ((action: String, seekSeconds: Double?) -> Unit)? = null
}
