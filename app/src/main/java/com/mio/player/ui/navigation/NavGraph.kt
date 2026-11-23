package com.mio.player.ui.navigation
object Routes {
    const val SPLASH="splash"; const val ONBOARDING="onboarding"; const val LOGIN="login"; const val GENRES="genres"
    const val HOME="home"; const val SEARCH="search"; const val LIBRARY="library"; const val PROFILE="profile"
    const val NOW_PLAYING="now_playing"; const val ALBUM="album"; const val ARTIST="artist"; const val ARTIST_DETAIL="artist_detail/{name}"; const val LYRICS="lyrics"; const val EQ="eq"
    const val LOG="log"
    // v1.1.25 — Settings sub-screens (grouped, not one long scroll)
    // and album detail by name (for "Đi tới album" in Now Playing).
    const val SETTINGS_SOUND="settings_sound"; const val SETTINGS_DISPLAY="settings_display"; const val SETTINGS_LIBRARY="settings_library"; const val SETTINGS_PLAYBACK="settings_playback"; const val SETTINGS_ABOUT="settings_about"
    const val ALBUM_DETAIL="album_detail/{album}"
}
