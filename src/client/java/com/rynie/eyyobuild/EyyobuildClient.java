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

public final class EyyobuildClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("eyyobuild", "main")
    );

    private static final KeyMapping OPEN_MENU = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.eyyobuild.open", InputConstants.Type.KEYSYM, InputConstants.KEY_B, CATEGORY)
    );

    private static final KeyMapping REFRESH = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.eyyobuild.refresh", InputConstants.Type.KEYSYM, InputConstants.KEY_R, CATEGORY)
    );

    private static final KeyMapping PREVIOUS = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.eyyobuild.previous", InputConstants.Type.KEYSYM, InputConstants.KEY_J, CATEGORY)
    );

    private static final KeyMapping NEXT = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.eyyobuild.next", InputConstants.Type.KEYSYM, InputConstants.KEY_K, CATEGORY)
    );

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_MENU.consumeClick()) {
                if (client.player == null) {
                    continue;
                }

                SchematicCatalog.refresh(FabricLoader.getInstance().getGameDir());
                client.gui.setScreen(new EyyobuildScreen());
            }

            while (REFRESH.consumeClick()) {
                if (client.player != null) {
                    SchematicCatalog.refresh(FabricLoader.getInstance().getGameDir());
                }
            }

            while (PREVIOUS.consumeClick()) {
                if (client.player != null) {
                    SchematicCatalog.previous();
                }
            }

            while (NEXT.consumeClick()) {
                if (client.player != null) {
                    SchematicCatalog.next();
                }
            }
        });
    }
}
