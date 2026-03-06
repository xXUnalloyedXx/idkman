package com.spotifycraft.api;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class SpotifyApi {

    public static final String CLIENT_ID     = "f86fe43ef37b49e8a1e9da592dc9f202";
    public static final String CLIENT_SECRET = "ede4083e0df74a1f965a14f0f3b653fe";

    private static final String REDIRECT_URI = "http://127.0.0.1:8765/callback";
    private static final String TOKEN_URL    = "https://accounts.spotify.com/api/token";
    private static final String API_BASE     = "https://api.spotify.com/v1";
    private static final String SCOPES       =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing playlist-read-private playlist-read-collaborative";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new GsonBuilder().create();

    private static String accessToken  = null;
    private static String refreshToken = null;
    private static long   tokenExpiry  = 0;

    private static final Object AUTH_LOCK = new Object();

    public static SpotifyTrack currentTrack   = null;
    public static boolean isPlaying           = false;
    public static int progressMs              = 0;
    public static int durationMs              = 1;
    public static float volume                = 50f;
    public static String lastError            = null;
    public static boolean isPremium           = false;
    public static String  username            = null;
    public static String  userImageUrl        = null;

    private static ScheduledExecutorService scheduler;
    private static Path tokenFile;

    public static void sendChat(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null)
            mc.player.sendMessage(Text.literal("[SpotifyCraft] " + msg), false);
    }

    public static void init() {
        tokenFile = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("spotify_tokens.json");
        loadTokens();
        if (isAuthenticated()) fetchUserProfile();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SpotifyCraft-Poller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(SpotifyApi::poll, 0, 3, TimeUnit.SECONDS);
    }

    public static String getAuthUrl() {
        String state = "spotifycraft";
        return "https://accounts.spotify.com/authorize"
                + "?client_id=" + CLIENT_ID
                + "&response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8)
                + "&state=" + state
                // Force showing the consent dialog, even if already authorized/logged in.
                + "&show_dialog=true";
    }

    public static void startAuth(Consumer<String> onError) {
        String state = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String url = "https://accounts.spotify.com/authorize"
                + "?client_id=" + CLIENT_ID
                + "&response_type=code"
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8)
                + "&state=" + state
                // Force showing the consent dialog, even if already authorized/logged in.
                + "&show_dialog=true";
        try {
            startCallbackServer(state, onError);
            MinecraftClient.getInstance().execute(() ->
                    MinecraftClient.getInstance().keyboard.setClipboard(url));
            try {
                new ProcessBuilder("powershell", "-Command",
                        "Start-Process '" + url + "'").start();
            } catch (Exception e1) {
                try {
                    java.awt.Desktop.getDesktop().browse(URI.create(url));
                } catch (Exception e2) {
                    // URL already on clipboard
                }
            }
        } catch (Exception e) {
            onError.accept("Auth error: " + e.getMessage());
        }
    }

    private static void startCallbackServer(String expectedState, Consumer<String> onError) {
        Thread t = new Thread(() -> {
            com.sun.net.httpserver.HttpServer server = null;
            try {
                server = com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress("127.0.0.1", 8765), 0);
                CountDownLatch latch = new CountDownLatch(1);
                final com.sun.net.httpserver.HttpServer finalServer = server;
                server.createContext("/callback", exchange -> {
                    try {
                        String query = exchange.getRequestURI().getQuery();
                        Map<String, String> params = parseQuery(query);
                        String code  = params.get("code");
                        String receivedState = params.get("state");
                        String html;
                        if (code != null && expectedState.equals(receivedState)) {
                            exchangeCode(code);
                            MinecraftClient.getInstance().execute(() -> {
                                net.minecraft.client.gui.screen.Screen s = MinecraftClient.getInstance().currentScreen;
                                if (s != null) s.init(s.width, s.height);
                            });
                            html = "<html><body style='font-family:sans-serif;text-align:center;padding-top:80px;background:#121212;color:white'>"
                                 + "<h2 style='color:#1DB954'>SpotifyCraft connected!</h2>"
                                 + "<p>You can close this tab and return to Minecraft.</p></body></html>";
                        } else {
                            html = "<html><body><h2>Auth failed - state mismatch</h2></body></html>";
                            onError.accept("OAuth state mismatch.");
                        }
                        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.getResponseBody().close();
                    } catch (Exception e) {
                        onError.accept("Callback error: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
                server.start();
                latch.await(5, TimeUnit.MINUTES);
            } catch (Exception e) {
                onError.accept("Callback server error: " + e.getMessage());
            } finally {
                if (server != null) server.stop(0);
            }
        }, "SpotifyCraft-Auth");
        t.setDaemon(true);
        t.start();
    }

    private static void exchangeCode(String code) throws Exception {
        String body = "grant_type=authorization_code"
                + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8);
        JsonObject json = postToken(body);
        if (json.has("error")) throw new Exception(json.get("error").getAsString());
        synchronized (AUTH_LOCK) {
            applyTokenResponse(json);
            saveTokensLocked();
        }
        fetchUserProfile();
    }

    private static void refreshAccessToken() throws Exception {
        final String rt;
        synchronized (AUTH_LOCK) {
            rt = refreshToken;
        }
        if (rt == null || rt.isBlank()) return;
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(rt, StandardCharsets.UTF_8);
        JsonObject json = postToken(body);
        if (json == null || !json.isJsonObject())
            throw new Exception("Refresh failed: empty response");
        if (json.has("error")) {
            String err = safeGetString(json, "error");
            if ("invalid_grant".equalsIgnoreCase(err)) {
                forceReconnect("Spotify session expired. Please reconnect.");
            }
            throw new Exception("Refresh failed: " + err);
        }
        synchronized (AUTH_LOCK) {
            // If user logged out while refresh was in-flight, do not resurrect tokens.
            if (refreshToken == null || !rt.equals(refreshToken)) return;
            applyTokenResponse(json);
            saveTokensLocked();
        }
    }

    private static JsonObject postToken(String body) throws Exception {
        String credentials = Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return GSON.fromJson(res.body(), JsonObject.class);
    }

    private static void applyTokenResponse(JsonObject json) {
        if (json == null || !json.has("access_token") || json.get("access_token").isJsonNull())
            throw new IllegalArgumentException("Token response missing access_token");
        if (!json.has("expires_in") || json.get("expires_in").isJsonNull())
            throw new IllegalArgumentException("Token response missing expires_in");
        accessToken = json.get("access_token").getAsString();
        tokenExpiry = Instant.now().getEpochSecond() + json.get("expires_in").getAsInt() - 30;
        if (json.has("refresh_token") && !json.get("refresh_token").isJsonNull())
            refreshToken = json.get("refresh_token").getAsString();
    }

    private static void fetchUserProfile() {
        CompletableFuture.runAsync(() -> {
            try {
                ensureToken();
                final String at;
                synchronized (AUTH_LOCK) { at = accessToken; }
                if (at == null) throw new IllegalStateException("Not authenticated");
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + "/me"))
                        .header("Authorization", "Bearer " + at)
                        .GET().build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                synchronized (AUTH_LOCK) {
                    if (accessToken == null || !at.equals(accessToken)) return;
                }
                JsonObject json = GSON.fromJson(res.body(), JsonObject.class);
                isPremium = json.has("product") && "premium".equals(json.get("product").getAsString());
                if (json.has("display_name") && !json.get("display_name").isJsonNull())
                    username = json.get("display_name").getAsString();
                try {
                    JsonArray images = json.getAsJsonArray("images");
                    if (images != null && images.size() > 0) {
                        userImageUrl = images.get(0).getAsJsonObject().get("url").getAsString();
                        ProfileImageCache.fetch(userImageUrl);
                    }
                } catch (Exception ignored) {}
            } catch (Exception e) {
                lastError = "Could not fetch profile: " + e.getMessage();
            }
        });
    }

    private static void saveTokens() {
        try {
            synchronized (AUTH_LOCK) {
                saveTokensLocked();
            }
        } catch (Exception ignored) {}
    }

    private static void saveTokensLocked() throws IOException {
        if (tokenFile == null) return;
        JsonObject obj = new JsonObject();
        obj.addProperty("access_token",  accessToken);
        obj.addProperty("refresh_token", refreshToken);
        obj.addProperty("expires_at",    tokenExpiry);
        Files.writeString(tokenFile, GSON.toJson(obj));
    }

    private static void loadTokens() {
        try {
            if (tokenFile == null || !Files.exists(tokenFile)) return;
            JsonObject obj = GSON.fromJson(Files.readString(tokenFile), JsonObject.class);
            synchronized (AUTH_LOCK) {
                accessToken  = safeGetString(obj, "access_token");
                refreshToken = safeGetString(obj, "refresh_token");
                tokenExpiry  = obj != null && obj.has("expires_at") && !obj.get("expires_at").isJsonNull()
                        ? obj.get("expires_at").getAsLong()
                        : 0;
                if (accessToken != null && accessToken.isBlank()) accessToken = null;
                if (refreshToken != null && refreshToken.isBlank()) refreshToken = null;
            }
        } catch (Exception ignored) {}
    }

    public static void logout() {
        forceReconnect(null);
    }

    public static boolean isAuthenticated() {
        synchronized (AUTH_LOCK) {
            return accessToken != null || refreshToken != null;
        }
    }

    private static void poll() {
        try {
            ensureToken();
            final String at;
            synchronized (AUTH_LOCK) {
                at = accessToken;
            }
            if (at == null) return;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/me/player"))
                    .header("Authorization", "Bearer " + at)
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            // If user logged out while request was in-flight, ignore the response.
            synchronized (AUTH_LOCK) {
                if (accessToken == null || !at.equals(accessToken)) return;
            }
            if (res.statusCode() == 200 && res.body() != null && !res.body().isBlank()) {
                JsonObject json = GSON.fromJson(res.body(), JsonObject.class);
                isPlaying  = json.get("is_playing").getAsBoolean();
                progressMs = json.get("progress_ms").getAsInt();
                if (json.has("device") && !json.get("device").isJsonNull()) {
                    JsonObject dev = json.getAsJsonObject("device");
                    if (dev.has("volume_percent") && !dev.get("volume_percent").isJsonNull())
                        volume = dev.get("volume_percent").getAsFloat();
                }
                JsonObject item = json.getAsJsonObject("item");
                if (item != null && !item.isJsonNull()) {
                    String id    = item.get("id").getAsString();
                    String title = item.get("name").getAsString();
                    durationMs   = item.get("duration_ms").getAsInt();
                    String artist = item.getAsJsonArray("artists")
                            .get(0).getAsJsonObject().get("name").getAsString();
                    String album  = item.getAsJsonObject("album").get("name").getAsString();
                    String artUrl = item.getAsJsonObject("album")
                            .getAsJsonArray("images").get(0).getAsJsonObject()
                            .get("url").getAsString();
                    boolean trackChanged = currentTrack == null || !currentTrack.id().equals(id);
                    currentTrack = new SpotifyTrack(id, title, artist, album, artUrl, durationMs);
                    if (trackChanged) {
                        AlbumArtCache.fetch(artUrl);
                        NowPlayingToast.show(title, artist);
                        lastError = null;
                    }
                } else {
                    currentTrack = null;
                }
            } else if (res.statusCode() == 204) {
                isPlaying = false;
                currentTrack = null;
                lastError = null;
            } else if (res.statusCode() == 401) {
                // Token invalid/revoked; attempt refresh once (if possible), otherwise force reconnect.
                synchronized (AUTH_LOCK) { accessToken = null; }
                try {
                    refreshAccessToken();
                } catch (Exception refreshErr) {
                    forceReconnect("Spotify authorization expired. Please reconnect.");
                }
            } else if (res.statusCode() == 403) {
                // Some Spotify accounts/devices return 403 for /me/player; fall back to currently-playing.
                boolean ok = pollCurrentlyPlaying(at);
                if (!ok) lastError = "Playback state unavailable (403). Reconnect or check Spotify.";
            }
        } catch (Exception e) {
            lastError = "Poll error: " + e.getMessage();
        }
    }

    private static boolean pollCurrentlyPlaying(String at) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/me/player/currently-playing"))
                    .header("Authorization", "Bearer " + at)
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            synchronized (AUTH_LOCK) {
                if (accessToken == null || !at.equals(accessToken)) return true;
            }
            if (res.statusCode() == 200 && res.body() != null && !res.body().isBlank()) {
                JsonObject json = GSON.fromJson(res.body(), JsonObject.class);
                if (json.has("is_playing") && !json.get("is_playing").isJsonNull())
                    isPlaying = json.get("is_playing").getAsBoolean();
                if (json.has("progress_ms") && !json.get("progress_ms").isJsonNull())
                    progressMs = json.get("progress_ms").getAsInt();
                JsonObject item = json.getAsJsonObject("item");
                if (item != null && !item.isJsonNull()) {
                    String id    = item.get("id").getAsString();
                    String title = item.get("name").getAsString();
                    durationMs   = item.get("duration_ms").getAsInt();
                    String artist = item.getAsJsonArray("artists")
                            .get(0).getAsJsonObject().get("name").getAsString();
                    String album  = item.getAsJsonObject("album").get("name").getAsString();
                    String artUrl = item.getAsJsonObject("album")
                            .getAsJsonArray("images").get(0).getAsJsonObject()
                            .get("url").getAsString();
                    boolean trackChanged = currentTrack == null || !currentTrack.id().equals(id);
                    currentTrack = new SpotifyTrack(id, title, artist, album, artUrl, durationMs);
                    if (trackChanged) {
                        AlbumArtCache.fetch(artUrl);
                        NowPlayingToast.show(title, artist);
                    }
                } else {
                    currentTrack = null;
                }
                // No device/volume info here; keep previous volume and premium flag from /me.
                return true;
            }
            if (res.statusCode() == 204) {
                isPlaying = false;
                currentTrack = null;
                lastError = null;
                return true;
            }
            if (res.statusCode() == 401) {
                synchronized (AUTH_LOCK) { accessToken = null; }
                try {
                    refreshAccessToken();
                } catch (Exception refreshErr) {
                    forceReconnect("Spotify authorization expired. Please reconnect.");
                }
                return true;
            }
            if (res.statusCode() == 403) {
                lastError = "Playback state requires Spotify Premium";
                return true;
            }
            lastError = "Currently playing failed (" + res.statusCode() + ")";
            return false;
        } catch (Exception e) {
            lastError = "Currently playing error: " + e.getMessage();
            return false;
        }
    }

    private static void ensureToken() throws Exception {
        final String at;
        final String rt;
        final long exp;
        synchronized (AUTH_LOCK) {
            at = accessToken;
            rt = refreshToken;
            exp = tokenExpiry;
        }
        if (at == null && rt != null)
            refreshAccessToken();
        else if (at != null && Instant.now().getEpochSecond() > exp)
            refreshAccessToken();
    }

    public static void play()     { sendPutAsync("/me/player/play", null); }
    public static void pause()    { sendPutAsync("/me/player/pause", null); }
    public static void next()     { sendPostAsync("/me/player/next"); }
    public static void previous() { sendPostAsync("/me/player/previous"); }

    public static void setVolume(int percent) {
        sendPutAsync("/me/player/volume?volume_percent=" + percent, null);
        volume = percent;
    }

    public static void playUri(String uri) {
        JsonObject body = new JsonObject();
        JsonArray uris = new JsonArray();
        uris.add(uri);
        body.add("uris", uris);
        sendPutAsync("/me/player/play", GSON.toJson(body));
    }

    public static void playContext(String contextUri) {
        JsonObject body = new JsonObject();
        body.addProperty("context_uri", contextUri);
        sendPutAsync("/me/player/play", GSON.toJson(body));
    }

    // Fetch all user playlists
    public static List<SearchResult> getUserPlaylists() {
        List<SearchResult> results = new ArrayList<>();
        try {
            ensureToken();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/me/playlists?limit=50"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject json = GSON.fromJson(res.body(), JsonObject.class);
            if (!json.has("items")) {
                lastError = "Playlists error: " + res.body();
                return results;
            }
            for (JsonElement el : json.getAsJsonArray("items")) {
                if (el.isJsonNull()) continue;
                JsonObject obj = el.getAsJsonObject();
                String id   = obj.get("id").getAsString();
                String name = obj.get("name").getAsString();
                String uri  = obj.get("uri").getAsString();
                int tracks  = obj.getAsJsonObject("tracks").get("total").getAsInt();
                String artUrl = null;
                try {
                    JsonArray images = obj.getAsJsonArray("images");
                    if (images != null && images.size() > 0)
                        artUrl = images.get(0).getAsJsonObject().get("url").getAsString();
                } catch (Exception ignored) {}
                results.add(new SearchResult(id, name, tracks + " tracks", "playlist", uri, artUrl, true));
            }
        } catch (Exception e) {
            lastError = "Playlist fetch error: " + e.getMessage();
        }
        return results;
    }

    public static List<SearchResult> search(String query) {
        List<SearchResult> results = new ArrayList<>();
        try {
            ensureToken();
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // Search tracks and playlists only - simpler and more reliable
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/search?q=" + encoded
                            + "&type=track,playlist,album&limit=5"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                lastError = "Search failed (" + res.statusCode() + "): " + res.body();
                return results;
            }
            JsonObject json = GSON.fromJson(res.body(), JsonObject.class);
            addResults(results, json, "tracks",    "track");
            addResults(results, json, "playlists", "playlist");
            addResults(results, json, "albums",    "album");
        } catch (Exception e) {
            lastError = "Search error: " + e.getMessage();
        }
        return results;
    }

    private static void addResults(List<SearchResult> out, JsonObject root, String key, String type) {
        try {
            JsonArray items = root.getAsJsonObject(key).getAsJsonArray("items");
            for (JsonElement el : items) {
                if (el.isJsonNull()) continue;
                JsonObject obj = el.getAsJsonObject();
                String id   = obj.get("id").getAsString();
                String name = obj.get("name").getAsString();
                String uri  = obj.get("uri").getAsString();
                String subtitle = switch (type) {
                    case "track"    -> obj.getAsJsonArray("artists")
                            .get(0).getAsJsonObject().get("name").getAsString();
                    case "album"    -> obj.getAsJsonArray("artists")
                            .get(0).getAsJsonObject().get("name").getAsString();
                    case "playlist" -> obj.has("owner") ? obj.getAsJsonObject("owner")
                            .get("display_name").getAsString() : "Playlist";
                    default -> "";
                };
                String artUrl = null;
                try {
                    JsonArray images = type.equals("track")
                            ? obj.getAsJsonObject("album").getAsJsonArray("images")
                            : obj.getAsJsonArray("images");
                    if (images != null && images.size() > 0)
                        artUrl = images.get(0).getAsJsonObject().get("url").getAsString();
                } catch (Exception ignored) {}
                boolean isContext = !type.equals("track");
                out.add(new SearchResult(id, name, subtitle, type, uri, artUrl, isContext));
            }
        } catch (Exception ignored) {}
    }

    private static void sendPutAsync(String path, String jsonBody) {
        CompletableFuture.runAsync(() -> {
            try {
                ensureToken();
                final String at;
                synchronized (AUTH_LOCK) { at = accessToken; }
                if (at == null) throw new IllegalStateException("Not authenticated");
                String body = (jsonBody != null) ? jsonBody : "";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + path))
                        .header("Authorization", "Bearer " + at)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 403)
                    lastError = "Error 403: " + res.body();
                else if (res.statusCode() == 404)
                    lastError = "No active device - open Spotify on a device first";
                else if (res.statusCode() >= 400)
                    lastError = "Error " + res.statusCode() + ": " + res.body();
                else
                    lastError = null;
                // Always log result to chat for debugging
                MinecraftClient.getInstance().execute(() ->
                    sendChat("PUT " + path + " -> " + res.statusCode() +
                        (lastError != null ? " | " + lastError : " OK")));
            } catch (Exception e) {
                lastError = e.getMessage();
                MinecraftClient.getInstance().execute(() -> sendChat("PUT Exception: " + e.getMessage()));
            }
        });
    }

    private static void sendPostAsync(String path) {
        CompletableFuture.runAsync(() -> {
            try {
                ensureToken();
                final String at;
                synchronized (AUTH_LOCK) { at = accessToken; }
                if (at == null) throw new IllegalStateException("Not authenticated");
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + path))
                        .header("Authorization", "Bearer " + at)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(""))
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 403)
                    lastError = "Error 403: " + res.body();
                else if (res.statusCode() == 404)
                    lastError = "No active device - open Spotify on a device first";
                else if (res.statusCode() >= 400)
                    lastError = "Error " + res.statusCode() + ": " + res.body();
                else
                    lastError = null;
                MinecraftClient.getInstance().execute(() ->
                    sendChat("POST " + path + " -> " + res.statusCode() +
                        (lastError != null ? " | " + lastError : " OK")));
            } catch (Exception e) {
                lastError = e.getMessage();
                MinecraftClient.getInstance().execute(() -> sendChat("POST Exception: " + e.getMessage()));
            }
        });
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2)
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return map;
    }

    public static String formatMs(int ms) {
        int s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private static void forceReconnect(String reason) {
        synchronized (AUTH_LOCK) {
            accessToken = null;
            refreshToken = null;
            tokenExpiry = 0;
        }
        currentTrack = null;
        username     = null;
        userImageUrl = null;
        isPlaying    = false;
        isPremium    = false;
        lastError    = reason;
        try {
            if (tokenFile != null) Files.deleteIfExists(tokenFile);
            Path fallback = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("spotify_tokens.json");
            Files.deleteIfExists(fallback);
        } catch (Exception ignored) {}
    }

    private static String safeGetString(JsonObject obj, String key) {
        try {
            if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
            return obj.get(key).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
