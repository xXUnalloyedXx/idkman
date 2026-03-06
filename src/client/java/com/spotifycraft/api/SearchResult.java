package com.spotifycraft.api;

public record SearchResult(
        String id,
        String name,
        String subtitle,
        String type,
        String uri,
        String artUrl,
        boolean isContext
) {}
