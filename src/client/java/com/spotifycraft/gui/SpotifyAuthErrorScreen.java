package com.spotifycraft.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class SpotifyAuthErrorScreen extends Screen {

    private final String error;

    public SpotifyAuthErrorScreen(String error) {
        super(Text.literal("Spotify Auth Error"));
        this.error = error;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"),
                        b -> client.setScreen(new SpotifyScreen()))
                .dimensions(width / 2 - 60, height / 2 + 20, 120, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Spotify authentication failed"),
                width / 2, height / 2 - 20, 0xFF5555);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(error), width / 2, height / 2, 0xAAAAAA);
        super.render(ctx, mx, my, delta);
    }
}
