package com.spotifycraft.gui;

import com.spotifycraft.api.*;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SpotifyScreen extends Screen {

    private static final int PANEL_W   = 320;
    private static final int TAB_H     = 18;
    private static final int CONTENT_H = 200;
    private static final int PANEL_H   = TAB_H + CONTENT_H;

    private static final int ART_SIZE  = 48;
    private static final int PAD       = 10;
    private static final int ROW_H     = 24;
    private static final int LIST_VISIBLE = 7;

    private static final int TAB_NOW_PLAYING = 0;
    private static final int TAB_SEARCH      = 1;
    private static final int TAB_PLAYLIST    = 2;
    private static final int TAB_PROFILE     = 3;

    private int px, py, contentY;
    private int activeTab = TAB_NOW_PLAYING;
    private String authStatus = null;

    private ButtonWidget playPauseBtn;
    private TextFieldWidget searchField;
    private final List<SearchResult> searchResults = new ArrayList<>();
    private boolean searching = false;

    private final List<SearchResult> playlists = new ArrayList<>();
    private boolean loadingPlaylists = false;
    private int playlistScroll = 0;

    public SpotifyScreen() {
        super(Text.literal("SpotifyCraft"));
    }

    @Override
    protected void init() {
        px       = (width  - PANEL_W) / 2;
        py       = (height - PANEL_H) / 2;
        contentY = py + TAB_H;

        if (!SpotifyApi.isAuthenticated()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Connect Spotify"),
                    b -> {
                        authStatus = "URL copied! Paste in browser, then wait...";
                        SpotifyApi.startAuth(err -> MinecraftClient.getInstance().execute(
                                () -> authStatus = "Error: " + err));
                    })
                    .dimensions(px + (PANEL_W - 160) / 2, py + 80, 160, 20).build());
            return;
        }

        // 4 equal tabs
        int tabW = PANEL_W / 4;
        String[] tabNames = {"Now Playing", "Search", "Playlists", "Profile"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            addDrawableChild(ButtonWidget.builder(Text.literal(tabNames[i]),
                    b -> switchTab(idx))
                    .dimensions(px + tabW * i, py, tabW, TAB_H).build());
        }

        initTabWidgets();
    }

    private void switchTab(int tab) {
        activeTab = tab;
        clearAndInit();
        if (tab == TAB_PLAYLIST && playlists.isEmpty() && !loadingPlaylists)
            loadPlaylists();
    }

    private void initTabWidgets() {
        if (activeTab == TAB_NOW_PLAYING) {
            int btnW = 56, gap = 6;
            int ctrlX = px + (PANEL_W - (btnW * 3 + gap * 2)) / 2;
            int ctrlY = contentY + ART_SIZE + 30;

            addDrawableChild(ButtonWidget.builder(Text.literal("|<"), b -> SpotifyApi.previous())
                    .dimensions(ctrlX, ctrlY, btnW, 20).build());
            playPauseBtn = ButtonWidget.builder(
                    Text.literal(SpotifyApi.isPlaying ? "||" : ">"),
                    b -> { if (SpotifyApi.isPlaying) SpotifyApi.pause(); else SpotifyApi.play(); })
                    .dimensions(ctrlX + btnW + gap, ctrlY, btnW, 20).build();
            addDrawableChild(playPauseBtn);
            addDrawableChild(ButtonWidget.builder(Text.literal(">|"), b -> SpotifyApi.next())
                    .dimensions(ctrlX + (btnW + gap) * 2, ctrlY, btnW, 20).build());

            int volY = ctrlY + 26;
            addDrawableChild(ButtonWidget.builder(Text.literal("Vol -"),
                    b -> SpotifyApi.setVolume(Math.max(0, (int) SpotifyApi.volume - 10)))
                    .dimensions(px + PAD, volY, 55, 16).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Vol +"),
                    b -> SpotifyApi.setVolume(Math.min(100, (int) SpotifyApi.volume + 10)))
                    .dimensions(px + PAD + 61, volY, 55, 16).build());

        } else if (activeTab == TAB_SEARCH) {
            int searchBtnW = 52;
            int searchFieldW = PANEL_W - PAD * 2 - searchBtnW - 4;
            searchField = new TextFieldWidget(textRenderer,
                    px + PAD, contentY + 6, searchFieldW, 16, Text.literal("Search"));
            searchField.setMaxLength(100);
            searchField.setPlaceholder(Text.literal("Search songs, albums, playlists..."));
            addDrawableChild(searchField);
            addDrawableChild(ButtonWidget.builder(Text.literal("Search"), b -> doSearch())
                    .dimensions(px + PAD + searchFieldW + 4, contentY + 6, searchBtnW, 16).build());

            int listY = contentY + 28;
            for (int i = 0; i < searchResults.size(); i++) {
                final SearchResult r = searchResults.get(i);
                int rowY = listY + i * ROW_H;
                addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                    if (r.isContext()) SpotifyApi.playContext(r.uri());
                    else SpotifyApi.playUri(r.uri());
                }).dimensions(px + PAD, rowY, PANEL_W - PAD * 2, ROW_H - 2).build());
            }

        } else if (activeTab == TAB_PLAYLIST) {
            int scrollX = px + PANEL_W - 44;
            int scrollY = contentY + CONTENT_H - 20;
            addDrawableChild(ButtonWidget.builder(Text.literal("▲"),
                    b -> { playlistScroll = Math.max(0, playlistScroll - 1); clearAndInit(); })
                    .dimensions(scrollX, scrollY, 20, 16).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("▼"),
                    b -> { playlistScroll = Math.min(Math.max(0, playlists.size() - LIST_VISIBLE), playlistScroll + 1); clearAndInit(); })
                    .dimensions(scrollX + 22, scrollY, 20, 16).build());

            int listY = contentY + 4;
            int rowW = PANEL_W - PAD * 2 - 48;
            int end = Math.min(playlists.size(), playlistScroll + LIST_VISIBLE);
            for (int i = playlistScroll; i < end; i++) {
                final SearchResult p = playlists.get(i);
                int rowY = listY + (i - playlistScroll) * ROW_H;
                addDrawableChild(ButtonWidget.builder(Text.empty(),
                        b -> SpotifyApi.playContext(p.uri()))
                        .dimensions(px + PAD, rowY, rowW, ROW_H - 2).build());
            }

        } else if (activeTab == TAB_PROFILE) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Log Out"),
                    b -> MinecraftClient.getInstance().execute(() -> {
                        SpotifyApi.logout();
                        clearAndInit();
                    }))
                    .dimensions(px + (PANEL_W - 120) / 2, contentY + 130, 120, 20).build());
        }
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(px, py, px + PANEL_W, py + PANEL_H, 0xF0111111);
        ctx.fill(px, py, px + PANEL_W, py + 3, 0xFF1DB954);

        if (!SpotifyApi.isAuthenticated()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Connect your Spotify account"),
                    px + PANEL_W / 2, py + 50, 0xFFFFFF);
            if (authStatus != null)
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(authStatus),
                        px + PANEL_W / 2, py + 110, 0x1DB954);
            super.render(ctx, mx, my, delta);
            return;
        }

        // Active tab underline
        int tabW = PANEL_W / 4;
        ctx.fill(px + activeTab * tabW, py + TAB_H - 2,
                px + (activeTab + 1) * tabW, py + TAB_H, 0xFF1DB954);

        if (activeTab == TAB_NOW_PLAYING)  renderNowPlaying(ctx);
        else if (activeTab == TAB_SEARCH)  renderSearch(ctx, mx, my);
        else if (activeTab == TAB_PLAYLIST) renderPlaylist(ctx, mx, my);
        else if (activeTab == TAB_PROFILE) renderProfile(ctx);

        super.render(ctx, mx, my, delta);

        // Non-intrusive status/error line (replaces the old red bar)
        if (SpotifyApi.lastError != null && !SpotifyApi.lastError.isBlank()) {
            String msg = textRenderer.trimToWidth(SpotifyApi.lastError, PANEL_W - PAD * 2);
            ctx.drawTextWithShadow(textRenderer, msg, px + PAD, py + PANEL_H - 10, 0xFFCC66);
        }
    }

    private void renderNowPlaying(DrawContext ctx) {
        SpotifyTrack track = SpotifyApi.currentTrack;

        int artX = px + PAD, artY = contentY + 4;
        Identifier artTex = (track != null) ? AlbumArtCache.get(track.artUrl()) : null;
        if (track != null && artTex == null && track.artUrl() != null)
            AlbumArtCache.fetch(track.artUrl());
        if (artTex != null) {
            drawFullTexture(ctx, artTex, artX, artY, ART_SIZE, ART_SIZE);
        } else {
            ctx.fill(artX, artY, artX + ART_SIZE, artY + ART_SIZE, 0xFF1DB954);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u266B"),
                    artX + ART_SIZE / 2, artY + ART_SIZE / 2 - 4, 0xFF000000);
        }

        int infoX = artX + ART_SIZE + PAD;
        int infoW = PANEL_W - ART_SIZE - PAD * 3;
        if (track != null) {
            drawTruncated(ctx, track.title(),  infoX, contentY + 8,  infoW, 0xFFFFFF);
            drawTruncated(ctx, track.artist(), infoX, contentY + 20, infoW, 0xAAAAAA);
            drawTruncated(ctx, track.album(),  infoX, contentY + 32, infoW, 0x777777);
        } else {
            ctx.drawTextWithShadow(textRenderer, Text.literal("Nothing playing"),
                    infoX, contentY + 8, 0xAAAAAA);
            ctx.drawTextWithShadow(textRenderer, Text.literal("Open Spotify on a device first"),
                    infoX, contentY + 20, 0x555555);
        }

        int pbX = px + PAD, pbY = contentY + ART_SIZE + 8, pbW = PANEL_W - PAD * 2;
        ctx.fill(pbX, pbY, pbX + pbW, pbY + 3, 0xFF333333);
        if (SpotifyApi.durationMs > 0) {
            float pct = (float) SpotifyApi.progressMs / SpotifyApi.durationMs;
            ctx.fill(pbX, pbY, pbX + (int)(pbW * pct), pbY + 3, 0xFF1DB954);
        }
        ctx.drawTextWithShadow(textRenderer, SpotifyApi.formatMs(SpotifyApi.progressMs),
                pbX, pbY + 5, 0x888888);
        String total = SpotifyApi.formatMs(SpotifyApi.durationMs);
        ctx.drawTextWithShadow(textRenderer, total,
                pbX + pbW - textRenderer.getWidth(total), pbY + 5, 0x888888);

        ctx.drawTextWithShadow(textRenderer,
                Text.literal("Vol: " + (int) SpotifyApi.volume + "%"),
                px + PAD + 122, contentY + ART_SIZE + 60, 0xAAAAAA);

        if (playPauseBtn != null)
            playPauseBtn.setMessage(Text.literal(SpotifyApi.isPlaying ? "||" : ">"));
    }

    private void renderSearch(DrawContext ctx, int mx, int my) {
        int listY = contentY + 28;
        if (searching) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Searching..."),
                    px + PANEL_W / 2, listY + 10, 0xAAAAAA);
            return;
        }
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult r = searchResults.get(i);
            int rowY = listY + i * ROW_H;
            boolean hovered = mx >= px + PAD && mx <= px + PANEL_W - PAD
                    && my >= rowY && my <= rowY + ROW_H - 2;
            ctx.fill(px + PAD, rowY, px + PANEL_W - PAD, rowY + ROW_H - 2,
                    hovered ? 0xFF1A3A1A : 0xFF1A1A1A);
            int badgeColor = switch (r.type()) {
                case "track" -> 0xFF1DB954;
                case "album" -> 0xFF4488FF;
                default      -> 0xFFAA44FF;
            };
            ctx.fill(px + PAD, rowY, px + PAD + 3, rowY + ROW_H - 2, badgeColor);
            drawTruncated(ctx, r.name(),
                    px + PAD + 6, rowY + 3, PANEL_W - PAD * 2 - 10, 0xFFFFFF);
            drawTruncated(ctx, r.subtitle() + " · " + r.type(),
                    px + PAD + 6, rowY + 13, PANEL_W - PAD * 2 - 10, 0x888888);
        }
    }

    private void renderPlaylist(DrawContext ctx, int mx, int my) {
        int listY = contentY + 4;
        if (loadingPlaylists) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Loading playlists..."),
                    px + PANEL_W / 2, listY + 10, 0xAAAAAA);
            return;
        }
        if (playlists.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("No playlists found"),
                    px + PANEL_W / 2, listY + 10, 0x888888);
            return;
        }
        int rowW = PANEL_W - PAD * 2 - 48;
        int end = Math.min(playlists.size(), playlistScroll + LIST_VISIBLE);
        for (int i = playlistScroll; i < end; i++) {
            SearchResult p = playlists.get(i);
            int rowY = listY + (i - playlistScroll) * ROW_H;
            boolean hovered = mx >= px + PAD && mx <= px + PAD + rowW
                    && my >= rowY && my <= rowY + ROW_H - 2;
            ctx.fill(px + PAD, rowY, px + PAD + rowW, rowY + ROW_H - 2,
                    hovered ? 0xFF1A3A1A : 0xFF1A1A1A);
            ctx.fill(px + PAD, rowY, px + PAD + 3, rowY + ROW_H - 2, 0xFFAA44FF);
            drawTruncated(ctx, p.name(),     px + PAD + 6, rowY + 3,  rowW - 10, 0xFFFFFF);
            drawTruncated(ctx, p.subtitle(), px + PAD + 6, rowY + 13, rowW - 10, 0x888888);
        }
        if (playlists.size() > LIST_VISIBLE)
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal((playlistScroll + 1) + "-" + end + " / " + playlists.size()),
                    px + PAD, contentY + CONTENT_H - 18, 0x666666);
    }

    private void renderProfile(DrawContext ctx) {
        int centerX = px + PANEL_W / 2;
        int avatarSize = 40;
        int avatarX = centerX - avatarSize / 2;
        int avatarY = contentY + 20;

        Identifier pfpTex = ProfileImageCache.getTexture();
        if (pfpTex == null && SpotifyApi.userImageUrl != null)
            ProfileImageCache.fetch(SpotifyApi.userImageUrl);
        if (pfpTex != null) {
            drawFullTexture(ctx, pfpTex, avatarX, avatarY, avatarSize, avatarSize);
        } else {
            // Avatar placeholder (green circle-ish square)
            ctx.fill(avatarX, avatarY, avatarX + avatarSize, avatarY + avatarSize, 0xFF1DB954);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("\u263A"),
                    centerX, avatarY + avatarSize / 2 - 4, 0xFF000000);
        }

        // Username
        String name = SpotifyApi.username != null ? SpotifyApi.username : "Loading...";
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(name), centerX, avatarY + avatarSize + 10, 0xFFFFFF);

        // Account type
        String type = SpotifyApi.isPremium ? "Spotify Premium" : "Spotify Free";
        int typeColor = SpotifyApi.isPremium ? 0xFF1DB954 : 0xAAAAAA;
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(type), centerX, avatarY + avatarSize + 22, typeColor);

        // Divider
        ctx.fill(px + PAD * 3, avatarY + avatarSize + 36,
                px + PANEL_W - PAD * 3, avatarY + avatarSize + 37, 0xFF333333);
    }

    private void loadPlaylists() {
        loadingPlaylists = true;
        playlists.clear();
        CompletableFuture.supplyAsync(SpotifyApi::getUserPlaylists)
                .thenAccept(results -> MinecraftClient.getInstance().execute(() -> {
                    playlists.addAll(results);
                    loadingPlaylists = false;
                    clearAndInit();
                }));
    }

    private void doSearch() {
        if (searchField == null) return;
        String q = searchField.getText().trim();
        if (q.isEmpty()) return;
        searching = true;
        searchResults.clear();
        clearAndInit();
        CompletableFuture.supplyAsync(() -> SpotifyApi.search(q))
                .thenAccept(results -> MinecraftClient.getInstance().execute(() -> {
                    searchResults.clear();
                    searchResults.addAll(results);
                    searching = false;
                    clearAndInit();
                }));
    }

    private void drawTruncated(DrawContext ctx, String text, int x, int y, int maxW, int color) {
        if (text == null) return;
        if (textRenderer.getWidth(text) > maxW)
            text = textRenderer.trimToWidth(text, maxW - textRenderer.getWidth("...")) + "...";
        ctx.drawTextWithShadow(textRenderer, text, x, y, color);
    }

    private void drawFullTexture(DrawContext ctx, Identifier tex, int x, int y, int w, int h) {
        // Yarn/Loom version used here exposes the overload without a Z parameter.
        ctx.drawTexturedQuad(tex, x, x + w, y, y + h, 0f, 1f, 0f, 1f);
    }

    @Override
    public boolean shouldPause() { return false; }
}
