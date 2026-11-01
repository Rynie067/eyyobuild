package com.rynie.eyyobuild;

import com.mojang.blaze3d.platform.InputConstants;
import com.rynie.eyyobuild.schematic.SchematicCatalog;
import com.rynie.eyyobuild.ui.EyyobuildScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Exactly two configurable EYYOBUILD key mappings: UI and assistant toggle. */
public final class EyyobuildClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("eyyobuild", "main")
    );

    private static final KeyMapping OPEN_UI = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.eyyobuild.open", InputConstants.Type.KEYSYM, InputConstants.KEY_B, CATEGORY)
    );

    private static final KeyMapping TOGGLE_ASSISTANT = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.eyyobuild.toggle_assistant", InputConstants.Type.KEYSYM, InputConstants.KEY_H, CATEGORY)
    );

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_UI.consumeClick()) {
                if (client.player == null) continue;
                SchematicCatalog.refresh(FabricLoader.getInstance().getGameDir());
                client.gui.setScreen(new EyyobuildScreen());
            }

            while (TOGGLE_ASSISTANT.consumeClick()) {
                if (client.player == null || client.level == null) continue;
                String message = BuilderSession.toggle(SchematicCatalog.current());
                client.player.displayClientMessage(Component.literal(message), true);
            }

            if (client.player == null && BuilderSession.isActive()) {
                BuilderSession.stop();
            }
        });
    }
}
