package com.spotifycraft.gui;

import com.spotifycraft.api.SpotifyApi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

public class VolumeSlider extends ClickableWidget {

    private float value;

    public VolumeSlider(int x, int y, int width, int height, float initialValue) {
        super(x, y, width, height, Text.literal("Volume"));
        this.value = initialValue;
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF333333);
        int fillW = (int)(getWidth() * value);
        ctx.fill(getX(), getY(), getX() + fillW, getY() + getHeight(), 0xFF1DB954);
        int thumbX = getX() + fillW - 2;
        ctx.fill(thumbX, getY() - 2, thumbX + 4, getY() + getHeight() + 2, 0xFFFFFFFF);
        // Draw label using ctx directly
        ctx.drawCenteredTextWithShadow(
                net.minecraft.client.MinecraftClient.getInstance().textRenderer,
                Text.literal("Vol: " + (int)(value * 100) + "%"),
                getX() + getWidth() / 2, getY() - 10, 0xAAAAAA);
    }

    private void updateValue(double mouseX) {
        value = (float) Math.clamp((mouseX - getX()) / getWidth(), 0.0, 1.0);
        SpotifyApi.setVolume((int)(value * 100));
    }

    @Override
    public void appendClickableNarrations(NarrationMessageBuilder builder) {}
}
