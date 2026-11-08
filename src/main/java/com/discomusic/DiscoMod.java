package com.discomusic;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class DiscoMod implements ClientModInitializer {
    public static KeyMapping K_PLAY, K_PREV, K_NEXT, K_VUP, K_VDN, K_OPEN;

    @Override
    public void onInitializeClient() {
        Cfg.load();
        Player.setVolume(Cfg.I.volume);
        Player.refresh();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("discomusic", "main"));
        K_PLAY = reg("pause", GLFW.GLFW_KEY_K, cat);
        K_PREV = reg("prev", GLFW.GLFW_KEY_J, cat);
        K_NEXT = reg("next", GLFW.GLFW_KEY_L, cat);
        K_VUP = reg("vup", GLFW.GLFW_KEY_UP, cat);
        K_VDN = reg("vdn", GLFW.GLFW_KEY_DOWN, cat);
        K_OPEN = reg("open", GLFW.GLFW_KEY_M, cat);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (K_PLAY.consumeClick()) Player.toggle();
            while (K_PREV.consumeClick()) Player.prev();
            while (K_NEXT.consumeClick()) Player.next(false);
            boolean vol = false;
            while (K_VUP.consumeClick()) { Player.setVolume(Cfg.I.volume + 5); vol = true; }
            while (K_VDN.consumeClick()) { Player.setVolume(Cfg.I.volume - 5); vol = true; }
            if (vol) Cfg.save();
            while (K_OPEN.consumeClick()) if (mc.gui.screen() == null) mc.gui.setScreen(new DiscoScreen());
        });
    }

    private static KeyMapping reg(String id, int key, KeyMapping.Category cat) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.discomusic." + id, InputConstants.Type.KEYSYM, key, cat));
    }
}
