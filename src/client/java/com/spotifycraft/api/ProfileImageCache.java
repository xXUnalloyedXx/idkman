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
import java.util.concurrent.CompletableFuture;

public class ProfileImageCache {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static Identifier textureId = null;
    private static String loadedUrl    = null;
    private static boolean loading     = false;

    public static Identifier getTexture() { return textureId; }

    public static void fetch(String url) {
        if (url == null || url.equals(loadedUrl) || loading) return;
        loading = true;
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url)).GET().build();
                HttpResponse<InputStream> res = HTTP.send(req,
                        HttpResponse.BodyHandlers.ofInputStream());
                NativeImage img = NativeImage.read(res.body());
                MinecraftClient.getInstance().execute(() -> {
                    try {
                        NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "spotifycraft_pfp", img);
                        Identifier id = Identifier.of("spotifycraft", "pfp");
                        MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
                        textureId = id;
                        loadedUrl = url;
                    } catch (Exception ignored) {}
                    loading = false;
                });
            } catch (Exception e) {
                loading = false;
            }
        });
    }
}
