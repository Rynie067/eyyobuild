package com.rynie.eyyobuild;

import com.mojang.blaze3d.platform.InputConstants;
import com.rynie.eyyobuild.schematic.SchematicCatalog;
import com.rynie.eyyobuild.ui.EyyobuildScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Exactly two configurable EYYOBUILD key mappings: UI and build start/stop. */
public final class EyyobuildClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("eyyobuild", "main")
    );

    private static final KeyMapping OPEN_UI = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.eyyobuild.open", InputConstants.Type.KEYSYM, InputConstants.KEY_B, CATEGORY)
    );

    private static final KeyMapping TOGGLE_BUILD = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.eyyobuild.toggle_build", InputConstants.Type.KEYSYM, InputConstants.KEY_H, CATEGORY)
    );

    @Override
    public void onInitializeClient() {
        BuildSettings.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_UI.consumeClick()) {
                if (client.player == null) continue;
                SchematicCatalog.refresh(FabricLoader.getInstance().getGameDir());
                client.gui.setScreen(new EyyobuildScreen());
            }

            while (TOGGLE_BUILD.consumeClick()) {
                if (client.player == null || client.level == null) continue;
                BuilderSession.toggle(client, SchematicCatalog.current());
            }

            if (client.player == null && BuilderSession.isActive()) {
                BuilderSession.stop();
            }
            BuilderSession.tick(client);
        });
    }
}
