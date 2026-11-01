package com.rynie.eyyobuild.ui;

import com.rynie.eyyobuild.BuilderSession;
import com.rynie.eyyobuild.schematic.SchematicCatalog;
import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class EyyobuildScreen extends Screen {
    private Button assistantButton;

    public EyyobuildScreen() {
        super(Component.literal("EYYOBUILD"));
    }

    @Override
    protected void init() {
        int buttonWidth = 82;
        int gap = 5;
        int totalWidth = buttonWidth * 5 + gap * 4;
        int startX = (this.width - totalWidth) / 2;
        int buttonY = this.height / 2 + 105;

        this.addRenderableWidget(Button.builder(Component.literal("Onceki"), button -> {
            SchematicCatalog.previous();
        }).bounds(startX, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Sonraki"), button -> {
            SchematicCatalog.next();
        }).bounds(startX + buttonWidth + gap, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Yenile"), button -> {
            refreshView();
        }).bounds(startX + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20).build());

        this.assistantButton = this.addRenderableWidget(Button.builder(
                Component.literal(BuilderSession.isActive() ? "Duraklat" : "Baslat"),
                button -> {
                    String message = BuilderSession.toggle(SchematicCatalog.current());
                    if (this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.displayClientMessage(Component.literal(message), true);
                    }
                    updateAssistantButton();
                }
        ).bounds(startX + (buttonWidth + gap) * 3, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Kapat"), button -> {
            this.onClose();
        }).bounds(startX + (buttonWidth + gap) * 4, buttonY, buttonWidth, 20).build());
    }

    private void updateAssistantButton() {
        if (assistantButton != null) {
            assistantButton.setMessage(Component.literal(BuilderSession.isActive() ? "Duraklat" : "Baslat"));
        }
    }

    private void refreshView() {
        SchematicCatalog.refresh(FabricLoader.getInstance().getGameDir());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        updateAssistantButton();

        int panelWidth = 460;
        int panelHeight = 290;
        int left = this.width / 2 - panelWidth / 2;
        int top = this.height / 2 - panelHeight / 2;
        int right = left + panelWidth;
        int bottom = top + panelHeight;

        // Draw the panel before super so interactive widgets remain on top.
        graphics.fill(left, top, right, bottom, 0xE6121826);
        graphics.fill(left, top, right, top + 3, 0xFFB56BFF);
        graphics.fill(left + 16, top + 53, right - 16, top + 54, 0x553F526D);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(this.font, "EYYOBUILD", left + 20, top + 17, 0xFFFFFFFF, true);
        graphics.text(this.font, "SCHEMATIC BUILDER  /  MINECRAFT 26.2", left + 20, top + 35, 0xFFB8C1D1, false);

        SchematicSummary summary = SchematicCatalog.current();
        if (summary == null) {
            graphics.text(this.font, "Sematik bulunamadi.", left + 20, top + 82, 0xFFFFFFFF, true);
            graphics.text(this.font, ".litematic dosyalarini oyun klasorundeki schematics/ klasorune koy.", left + 20, top + 104, 0xFFD5DCE8, false);
            graphics.text(this.font, "Klasor: " + String.valueOf(SchematicCatalog.directory()), left + 20, top + 128, 0xFF9BA8BC, false);
            if (SchematicCatalog.lastError() != null) {
                graphics.text(this.font, trim(SchematicCatalog.lastError(), 68), left + 20, top + 150, 0xFFFFB7B7, false);
            }
        } else if (!summary.readable()) {
            graphics.text(this.font, summary.displayName(), left + 20, top + 82, 0xFFFFFFFF, true);
            graphics.text(this.font, "Bu sematik okunamadi:", left + 20, top + 106, 0xFFFF8E8E, false);
            graphics.text(this.font, trim(summary.error(), 68), left + 20, top + 126, 0xFFFFB7B7, false);
        } else {
            graphics.text(this.font, trim(summary.displayName(), 60), left + 20, top + 76, 0xFFFFFFFF, true);
            graphics.text(this.font,
                    "Boyut: " + summary.width() + " x " + summary.height() + " x " + summary.length(),
                    left + 20, top + 101, 0xFFD5DCE8, false);
            graphics.text(this.font,
                    "Blok sayisi: " + summary.totalBlocks() + "   |   Bolge: " + summary.regions().size(),
                    left + 20, top + 120, 0xFFD5DCE8, false);

            int lineY = top + 151;
            for (int i = 0; i < Math.min(summary.regions().size(), 5); i++) {
                SchematicSummary.RegionSummary region = summary.regions().get(i);
                String line = region.name() + "  " + region.width() + "x" + region.height() + "x"
                        + region.length() + "  |  palet: " + region.paletteEntries();
                graphics.text(this.font, trim(line, 64), left + 20, lineY, 0xFF9FB4D0, false);
                lineY += 14;
            }
        }

        String assistantState = BuilderSession.isActive()
                ? "YARDIMCI: AKTIF  (manuel takip)"
                : "YARDIMCI: DURDURULDU";
        int stateColor = BuilderSession.isActive() ? 0xFF8FE3B0 : 0xFFFFC58A;
        graphics.text(this.font, assistantState, left + 20, bottom - 58, stateColor, true);
        graphics.text(this.font,
                "Sematik: " + (SchematicCatalog.size() == 0 ? "0/0" :
                        (SchematicCatalog.selectedIndex() + 1) + "/" + SchematicCatalog.size()),
                left + 260, bottom - 58, 0xFFB8C1D1, false);
        graphics.text(this.font, "B: arayuz  |  H: yardimci baslat/durdur", left + 20, bottom - 38, 0xFFB8C1D1, false);
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
