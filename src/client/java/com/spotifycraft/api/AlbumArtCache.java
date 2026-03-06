package com.spotifycraft.api;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AlbumArtCache {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, Identifier> cache = new ConcurrentHashMap<>();
    private static final AtomicInteger counter = new AtomicInteger(0);

    public static Identifier get(String url) {
        return cache.get(url);
    }

    public static void fetch(String url) {
        if (url == null || cache.containsKey(url)) return;
        Thread t = new Thread(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET().build();
                HttpResponse<InputStream> res = HTTP.send(req,
                        HttpResponse.BodyHandlers.ofInputStream());
                NativeImage img = NativeImage.read(res.body());
                int idx = counter.incrementAndGet();
                Identifier id = Identifier.of("spotifycraft", "albumart_" + idx);
                MinecraftClient.getInstance().execute(() -> {
                    MinecraftClient.getInstance().getTextureManager()
                            .registerTexture(id, new NativeImageBackedTexture(
                                    () -> "spotifycraft:albumart_" + idx, img));
                    cache.put(url, id);
                });
            } catch (Exception ignored) {}
        }, "SpotifyCraft-ArtFetch");
        t.setDaemon(true);
        t.start();
    }
}
