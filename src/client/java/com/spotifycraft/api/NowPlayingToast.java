package com.spotifycraft.api;

public class NowPlayingToast {

    private static String title  = null;
    private static String artist = null;
    private static long   shownAt = 0;
    private static final long DURATION_MS = 4000;

    public static void show(String title, String artist) {
        NowPlayingToast.title   = title;
        NowPlayingToast.artist  = artist;
        NowPlayingToast.shownAt = System.currentTimeMillis();
    }

    public static boolean isVisible() {
        return title != null && (System.currentTimeMillis() - shownAt) < DURATION_MS;
    }

    public static float progress() {
        return Math.min(1f, (System.currentTimeMillis() - shownAt) / (float) DURATION_MS);
    }

    public static String getTitle()  { return title;  }
    public static String getArtist() { return artist; }
}
