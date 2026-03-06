package com.spotifycraft.api;

public record SpotifyTrack(
        String id,
        String title,
        String artist,
        String album,
        String artUrl,
        int durationMs
) {}
