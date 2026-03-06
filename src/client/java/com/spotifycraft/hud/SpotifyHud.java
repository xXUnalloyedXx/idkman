package com.spotifycraft.hud;

import com.spotifycraft.api.NowPlayingToast;
import com.spotifycraft.api.SpotifyApi;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public class SpotifyHud {

    public static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();

        renderProgressBar(ctx, mc, sw, sh);
        renderToast(ctx, mc, sw, sh);
    }

    private static void renderProgressBar(DrawContext ctx, MinecraftClient mc, int sw, int sh) {
        if (SpotifyApi.currentTrack == null) return;

        int barW = 182;
        int barH = 3;
        int barX = (sw - barW) / 2;
        int barY = sh - 32 - barH - 14;

        ctx.fill(barX, barY, barX + barW, barY + barH, 0x88000000);

        float pct = SpotifyApi.durationMs > 0
                ? (float) SpotifyApi.progressMs / SpotifyApi.durationMs : 0f;
        int fillW = (int) (barW * pct);
        int color = SpotifyApi.isPlaying ? 0xFF1DB954 : 0xFF888888;
        ctx.fill(barX, barY, barX + fillW, barY + barH, color);

        String elapsed = SpotifyApi.formatMs(SpotifyApi.progressMs);
        String total   = SpotifyApi.formatMs(SpotifyApi.durationMs);
        ctx.drawTextWithShadow(mc.textRenderer, elapsed, barX, barY - 9, 0xFFFFFFFF);
        ctx.drawTextWithShadow(mc.textRenderer, total,
                barX + barW - mc.textRenderer.getWidth(total), barY - 9, 0xFFFFFFFF);
    }

    private static void renderToast(DrawContext ctx, MinecraftClient mc, int sw, int sh) {
        if (!NowPlayingToast.isVisible()) return;

        float prog  = NowPlayingToast.progress();
        float alpha = prog < 0.10f ? prog / 0.10f
                    : prog > 0.80f ? (1f - prog) / 0.20f
                    : 1f;
        int a = (int) (alpha * 255);

        String title  = NowPlayingToast.getTitle();
        String artist = "by " + NowPlayingToast.getArtist();

        int iconSize = 20;
        int padding  = 5;
        int textW    = Math.max(mc.textRenderer.getWidth(title), mc.textRenderer.getWidth(artist));
        int boxW     = iconSize + padding * 3 + textW;
        int boxH     = iconSize + padding * 2;
        int boxX     = (sw - boxW) / 2;
        int barY     = sh - 32 - 3 - 14;
        int boxY     = barY - boxH - 4;

        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, (int)(a * 0.6f) << 24);

ctx.fill(boxX + padding, boxY + padding,
                boxX + padding + iconSize, boxY + padding + iconSize,
                (a << 24) | 0x1DB954);

        int tx = boxX + padding + iconSize + padding;
        int ty = boxY + padding + 1;
        ctx.drawTextWithShadow(mc.textRenderer, title,  tx, ty,      (a << 24) | 0xFFFFFF);
        ctx.drawTextWithShadow(mc.textRenderer, artist, tx, ty + 10, (a << 24) | 0xAAAAAA);
    }
}
