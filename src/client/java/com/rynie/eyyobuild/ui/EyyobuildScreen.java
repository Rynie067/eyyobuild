package com.rynie.eyyobuild.ui;

import com.rynie.eyyobuild.schematic.SchematicCatalog;
import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class EyyobuildScreen extends Screen {
    public EyyobuildScreen() {
        super(Component.literal("EYYOBUILD"));
    }

    @Override
    protected void init() {
        int buttonY = this.height - 48;
        int buttonWidth = 88;
        int gap = 8;
        int totalWidth = buttonWidth * 4 + gap * 3;
        int startX = (this.width - totalWidth) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Onceki"), button -> {
            SchematicCatalog.previous();
        }).bounds(startX, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Sonraki"), button -> {
            SchematicCatalog.next();
        }).bounds(startX + buttonWidth + gap, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Yenile"), button -> {
            refreshView();
        }).bounds(startX + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Kapat"), button -> {
            this.onClose();
        }).bounds(startX + (buttonWidth + gap) * 3, buttonY, buttonWidth, 20).build());
    }

    public void refreshView() {
        SchematicCatalog.refresh(FabricLoader.getInstance().getGameDir());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int left = this.width / 2 - 220;
        int top = this.height / 2 - 125;
        int right = left + 440;
        int bottom = top + 250;

        graphics.fill(left, top, right, bottom, 0xE6121826);
        graphics.fill(left, top, right, top + 3, 0xFFB56BFF);
        graphics.text(this.font, "EYYOBUILD  /  SCHEMATIC BUILDER", left + 20, top + 17, 0xFFFFFFFF, true);
        graphics.text(this.font, "Minecraft 26.2  |  Litematica .litematic", left + 20, top + 35, 0xFFB8C1D1, false);

        SchematicSummary summary = SchematicCatalog.current();
        if (summary == null) {
            graphics.text(this.font, "Henuz sematik bulunamadi.", left + 20, top + 82, 0xFFFFFFFF, true);
            graphics.text(this.font, "Dosyalarini oyun klasorundeki schematics/ klasorune koy.", left + 20, top + 102, 0xFFD5DCE8, false);
            graphics.text(this.font, "Klasor: " + String.valueOf(SchematicCatalog.directory()), left + 20, top + 124, 0xFF9BA8BC, false);
        } else if (!summary.readable()) {
            graphics.text(this.font, summary.displayName(), left + 20, top + 78, 0xFFFFFFFF, true);
            graphics.text(this.font, "Bu dosya okunamadi:", left + 20, top + 101, 0xFFFF8E8E, false);
            graphics.text(this.font, trim(summary.error(), 54), left + 20, top + 119, 0xFFFFB7B7, false);
        } else {
            graphics.text(this.font, summary.displayName(), left + 20, top + 72, 0xFFFFFFFF, true);
            graphics.text(this.font,
                    "Olculer: " + summary.width() + " x " + summary.height() + " x " + summary.length(),
                    left + 20, top + 96, 0xFFD5DCE8, false);
            graphics.text(this.font,
                    "Blok sayisi: " + summary.totalBlocks() + "   |   Bolge: " + summary.regions().size(),
                    left + 20, top + 114, 0xFFD5DCE8, false);

            int lineY = top + 142;
            for (int i = 0; i < Math.min(summary.regions().size(), 4); i++) {
                SchematicSummary.RegionSummary region = summary.regions().get(i);
                String line = region.name() + "  " + region.width() + "x" + region.height() + "x"
                        + region.length() + "  |  palette: " + region.paletteEntries();
                graphics.text(this.font, trim(line, 58), left + 20, lineY, 0xFF9FB4D0, false);
                lineY += 14;
            }
        }

        graphics.text(this.font,
                "Sematik: " + (SchematicCatalog.size() == 0 ? "0/0" : (SchematicCatalog.selectedIndex() + 1) + "/" + SchematicCatalog.size()),
                left + 20, bottom - 26, 0xFFB8C1D1, false);
        graphics.text(this.font, "Tuslar: B ac  |  R yenile  |  J/K degistir", left + 210, bottom - 26, 0xFFB8C1D1, false);
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
