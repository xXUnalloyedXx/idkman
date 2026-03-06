package com.spotifycraft.mixin.client;

import com.spotifycraft.api.SpotifyApi;
import com.spotifycraft.api.SpotifyTrack;
import com.spotifycraft.gui.SpotifyScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends net.minecraft.client.gui.screen.ingame.HandledScreen<net.minecraft.screen.PlayerScreenHandler> {

    public InventoryScreenMixin() {
        super(null, null, null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        int btnX = (width - backgroundWidth) / 2 - 26;
        int btnY = (height - backgroundHeight) / 2;
        addDrawableChild(ButtonWidget.builder(
                Text.literal("*"),
                b -> MinecraftClient.getInstance().setScreen(new SpotifyScreen()))
                .dimensions(btnX, btnY, 20, 20)
                .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                        Text.literal("Open Spotify Controls")))
                .build());
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(DrawContext ctx, int mx, int my, float delta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        SpotifyTrack track = SpotifyApi.currentTrack;
        if (track == null) return;

        int bgX = (width  - backgroundWidth)  / 2;
        int bgY = (height - backgroundHeight) / 2;
        int panelX = bgX - 6 - 96;
        int panelY = bgY;

        ctx.fill(panelX, panelY, panelX + 96, panelY + 76, 0xCC121212);
        ctx.fill(panelX, panelY, panelX + 96, panelY + 2, 0xFF1DB954);

        int artSize = 32;
        int artX = panelX + (96 - artSize) / 2;
        int artY = panelY + 6;
        ctx.fill(artX, artY, artX + artSize, artY + artSize, 0xFF1DB954);

        String title = mc.textRenderer.trimToWidth(track.title(), 90);
        ctx.drawCenteredTextWithShadow(mc.textRenderer,
                Text.literal(title), panelX + 48, artY + artSize + 4, 0xFFFFFF);

        String time = SpotifyApi.formatMs(SpotifyApi.progressMs)
                + " / " + SpotifyApi.formatMs(SpotifyApi.durationMs);
        ctx.drawCenteredTextWithShadow(mc.textRenderer,
                Text.literal(time), panelX + 48, artY + artSize + 14, 0x1DB954);

        int pbY = artY + artSize + 26;
        int pbX = panelX + 4;
        int pbW = 88;
        ctx.fill(pbX, pbY, pbX + pbW, pbY + 3, 0xFF333333);
        if (SpotifyApi.durationMs > 0) {
            float pct = (float) SpotifyApi.progressMs / SpotifyApi.durationMs;
            ctx.fill(pbX, pbY, pbX + (int)(pbW * pct), pbY + 3, 0xFF1DB954);
        }
    }
}
