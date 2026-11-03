package com.rynie.eyyobuild.ui;

import com.rynie.eyyobuild.BuildSettings;
import com.rynie.eyyobuild.BuilderSession;
import com.rynie.eyyobuild.schematic.SchematicCatalog;
import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class EyyobuildScreen extends Screen {
    private Button buildButton;
    private int placementRefreshTicks;

    public EyyobuildScreen() {
        super(Component.literal("EYYOBUILD"));
    }

    @Override
    protected void init() {
        int buttonWidth = 88;
        int gap = 4;
        int totalWidth = buttonWidth * 5 + gap * 4;
        int startX = (this.width - totalWidth) / 2;
        int buttonY = this.height / 2 + 116;

        this.addRenderableWidget(Button.builder(Component.literal("Önceki"), button -> {
            SchematicCatalog.previous();
        }).bounds(startX, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Sonraki"), button -> {
            SchematicCatalog.next();
        }).bounds(startX + buttonWidth + gap, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Yenile"), button -> {
            SchematicCatalog.refresh(FabricLoader.getInstance().getGameDir());
        }).bounds(startX + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20).build());

        this.buildButton = this.addRenderableWidget(Button.builder(
                Component.literal(BuilderSession.isActive() ? "Durdur" : "Başlat"),
                button -> {
                    Minecraft client = Minecraft.getInstance();
                    if (BuilderSession.isActive()) {
                        BuilderSession.stop();
                    } else {
                        BuilderSession.start(client, SchematicCatalog.current());
                    }
                    updateBuildButton();
                }
        ).bounds(startX + (buttonWidth + gap) * 3, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Kapat"), button -> this.onClose())
                .bounds(startX + (buttonWidth + gap) * 4, buttonY, buttonWidth, 20).build());

        int sliderWidth = 460;
        this.addRenderableWidget(new BuildRateSlider(
                (this.width - sliderWidth) / 2, this.height / 2 + 55, sliderWidth
        ));
    }

    @Override
    public void tick() {
        super.tick();
        if (++placementRefreshTicks >= 20) {
            placementRefreshTicks = 0;
            // While the menu is open, disabled/closed Litematica placements disappear automatically.
            SchematicCatalog.refresh(FabricLoader.getInstance().getGameDir());
        }
        updateBuildButton();
    }

    private void updateBuildButton() {
        if (buildButton != null) {
            buildButton.setMessage(Component.literal(BuilderSession.isActive() ? "Durdur" : "Başlat"));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        updateBuildButton();

        int panelWidth = 500;
        int panelHeight = 340;
        int left = this.width / 2 - panelWidth / 2;
        int top = this.height / 2 - panelHeight / 2;
        int right = left + panelWidth;
        int bottom = top + panelHeight;

        graphics.fill(left, top, right, bottom, 0xE6121826);
        graphics.fill(left, top, right, top + 3, 0xFFB56BFF);
        graphics.fill(left + 16, top + 53, right - 16, top + 54, 0x553F526D);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(this.font, "EYYOBUILD", left + 20, top + 17, 0xFFFFFFFF, true);
        graphics.text(this.font, "LITEMATICA + AUTO MATERIAL SUPPLY  /  MINECRAFT 26.2",
                left + 20, top + 35, 0xFFB8C1D1, false);
        graphics.text(this.font, "Açık şematik: " + (SchematicCatalog.size() == 0 ? "0" :
                        (SchematicCatalog.selectedIndex() + 1) + " / " + SchematicCatalog.size()),
                left + 20, top + 68, 0xFFB56BFF, true);

        SchematicSummary summary = SchematicCatalog.current();
        if (summary == null) {
            graphics.text(this.font, "Açık Litematica şematiği bulunamadı.",
                    left + 20, top + 101, 0xFFFFFFFF, true);
            graphics.text(this.font, "Litematica > Schematic Placements bölümünden bir yerleşimi etkinleştir.",
                    left + 20, top + 124, 0xFFD5DCE8, false);
            graphics.text(this.font, "Kapalı/devre dışı yerleşimler bu listede gösterilmez.",
                    left + 20, top + 144, 0xFF9FB4D0, false);
            if (SchematicCatalog.lastError() != null) {
                graphics.text(this.font, trim(SchematicCatalog.lastError(), 72),
                        left + 20, top + 169, 0xFFFFB7B7, false);
            }
        } else if (!summary.readable()) {
            graphics.text(this.font, trim(summary.displayName(), 64), left + 20, top + 101, 0xFFFFFFFF, true);
            graphics.text(this.font, "Etkin yerleşimin şematik dosyası okunamadı:",
                    left + 20, top + 124, 0xFFFF8E8E, false);
            graphics.text(this.font, trim(summary.error(), 72), left + 20, top + 144, 0xFFFFB7B7, false);
            graphics.text(this.font, trim(String.valueOf(summary.path()), 72),
                    left + 20, top + 164, 0xFF9FB4D0, false);
        } else {
            graphics.text(this.font, trim(summary.displayName(), 64), left + 20, top + 100, 0xFFFFFFFF, true);
            graphics.text(this.font, "Dosya: " + trim(summary.path().getFileName().toString(), 60),
                    left + 20, top + 122, 0xFF9FB4D0, false);
            graphics.text(this.font,
                    "Boyut: " + summary.width() + " x " + summary.height() + " x " + summary.length(),
                    left + 20, top + 144, 0xFFD5DCE8, false);
            graphics.text(this.font,
                    "Yerleştirilecek blok: " + summary.blocks().size() + "  |  Bölge: " + summary.regions().size(),
                    left + 20, top + 164, 0xFFD5DCE8, false);

            int lineY = top + 187;
            for (int i = 0; i < Math.min(summary.regions().size(), 3); i++) {
                SchematicSummary.RegionSummary region = summary.regions().get(i);
                String line = region.name() + "  " + region.width() + "x" + region.height() + "x"
                        + region.length() + "  |  palet: " + region.paletteEntries();
                graphics.text(this.font, trim(line, 68), left + 20, lineY, 0xFF9FB4D0, false);
                lineY += 14;
            }
        }

        graphics.text(this.font, "İşlem hızı (1 yavaş  -  25 en hızlı)",
                left + 20, top + 236, 0xFFB8C1D1, false);
        String buildState = BuilderSession.status();
        int stateColor = BuilderSession.isActive() ? 0xFF8FE3B0 : 0xFFFFC58A;
        graphics.text(this.font, "Durum: " + trim(buildState, 68), left + 20, bottom - 43, stateColor, true);
        graphics.text(this.font, "B: arayüz  |  H: oto inşaat + hareket aç/kapat",
                left + 20, bottom - 24, 0xFFB8C1D1, false);
    }

    @Override
    public void removed() {
        BuildSettings.save();
        super.removed();
    }

    private static final class BuildRateSlider extends AbstractSliderButton {
        private BuildRateSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.literal(""), BuildSettings.getSliderValue());
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(BuildSettings.getSpeedLabel()));
        }

        @Override
        protected void applyValue() {
            BuildSettings.setSliderValue(value);
            updateMessage();
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            BuildSettings.save();
        }
    }

    private static String trim(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
