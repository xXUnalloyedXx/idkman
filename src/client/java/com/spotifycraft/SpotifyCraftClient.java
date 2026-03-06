package com.spotifycraft;

import com.spotifycraft.api.SpotifyApi;
import com.spotifycraft.gui.SpotifyScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.option.KeyBinding.Category;
import org.lwjgl.glfw.GLFW;

public class SpotifyCraftClient implements ClientModInitializer {

	public static KeyBinding openGuiKey;
	public static KeyBinding playPauseKey;
	public static KeyBinding nextKey;
	public static KeyBinding prevKey;

	@Override
	public void onInitializeClient() {
		SpotifyApi.init();

		openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.spotifycraft.open",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_F9,
				Category.MISC));

		playPauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.spotifycraft.playpause",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_F7,
				Category.MISC));

		nextKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.spotifycraft.next",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_F8,
				Category.MISC));

		prevKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.spotifycraft.prev",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_F6,
				Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openGuiKey.wasPressed())
				client.setScreen(new SpotifyScreen());
			while (playPauseKey.wasPressed()) {
				if (SpotifyApi.isPlaying) SpotifyApi.pause();
				else SpotifyApi.play();
			}
			while (nextKey.wasPressed())
				SpotifyApi.next();
			while (prevKey.wasPressed())
				SpotifyApi.previous();
		});
	}
}
