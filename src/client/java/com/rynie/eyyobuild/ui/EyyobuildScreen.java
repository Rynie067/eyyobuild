package com.rynie.eyyobuild.ui;

import com.rynie.eyyobuild.BuildSettings;
import com.rynie.eyyobuild.BuilderSession;
import com.rynie.eyyobuild.ProxySettings;
import com.rynie.eyyobuild.SecondaryClientLauncher;
import com.rynie.eyyobuild.schematic.SchematicCatalog;
import com.rynie.eyyobuild.schematic.SchematicSummary;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class EyyobuildScreen extends Screen {
    private Button buildButton;
    private Button secondaryLaunchButton;
    private MultiLineEditBox secondaryNickInput;
    private boolean secondaryLaunchInProgress;
    private String launchStatus = "";
    private boolean launchStatusSuccess;

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

        this.addRenderableWidget(Button.builder(Component.literal("Kapat"), button -> {
            this.onClose();
        }).bounds(startX + (buttonWidth + gap) * 4, buttonY, buttonWidth, 20).build());

        int sliderWidth = 460;
        this.addRenderableWidget(new BuildRateSlider(
                (this.width - sliderWidth) / 2, this.height / 2 + 55, sliderWidth
        ));

        int panelLeft = this.width / 2 - 250;
        int accountRowY = this.height / 2 + 94;
        this.secondaryNickInput = MultiLineEditBox.builder()
                .setX(panelLeft + 20)
                .setY(accountRowY)
                .setPlaceholder(Component.literal("Örn. Oyuncu2"))
                .setShowBackground(true)
                .setShowDecorations(false)
                .build(this.font, 150, 20, Component.literal("İkinci hesap nicki"));
        this.secondaryNickInput.setCharacterLimit(16);
        this.secondaryNickInput.setLineLimit(1);
        this.secondaryNickInput.setValue(BuildSettings.getSecondaryAccountNickname());
        this.addRenderableWidget(this.secondaryNickInput);

        this.addRenderableWidget(Button.builder(Component.literal("Proxy Değiştir"), button -> {
            Minecraft.getInstance().setScreen(new EyyobuildProxyScreen(this));
        }).bounds(panelLeft + 176, accountRowY, 112, 20).build());

        this.secondaryLaunchButton = this.addRenderableWidget(Button.builder(
                Component.literal("İkinci Oyunu Başlat"),
                button -> launchSecondaryClient()
        ).bounds(panelLeft + 294, accountRowY, 186, 20).build());
    }

    private void launchSecondaryClient() {
        if (secondaryLaunchInProgress || secondaryNickInput == null) return;

        String nickname = secondaryNickInput.getValue().trim();
        if (!SecondaryClientLauncher.isValidNickname(nickname)) {
            launchStatus = "Nick 3-16 karakter olmalı (A-Z, 0-9, _).";
            launchStatusSuccess = false;
            return;
        }

        BuildSettings.setSecondaryAccountNickname(nickname);
        BuildSettings.save();
        ProxySettings.Snapshot proxy = ProxySettings.snapshot();
        secondaryLaunchInProgress = true;
        launchStatus = "İkinci oyun hazırlanıyor...";
        launchStatusSuccess = true;
        if (secondaryLaunchButton != null) {
            secondaryLaunchButton.setMessage(Component.literal("Hazırlanıyor..."));
        }

        Thread.ofVirtual().name("EYYOBUILD-secondary-client").start(() -> {
            SecondaryClientLauncher.LaunchResult result = SecondaryClientLauncher.launch(nickname, proxy);
            Minecraft.getInstance().execute(() -> {
                launchStatus = result.message();
                launchStatusSuccess = result.success();
                secondaryLaunchInProgress = false;
                if (secondaryLaunchButton != null) {
                    secondaryLaunchButton.setMessage(Component.literal("İkinci Oyunu Başlat"));
                }
            });
        });
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
        graphics.text(this.font, "SCHEMATIC AUTO BUILDER  /  MINECRAFT 26.2", left + 20, top + 35, 0xFFB8C1D1, false);

        SchematicSummary summary = SchematicCatalog.current();
        if (summary == null) {
            graphics.text(this.font, "Şema bulunamadı.", left + 20, top + 82, 0xFFFFFFFF, true);
            graphics.text(this.font, ".litematic dosyalarını oyun klasöründeki schematics/ klasörüne koy.", left + 20, top + 104, 0xFFD5DCE8, false);
            graphics.text(this.font, "Klasör: " + String.valueOf(SchematicCatalog.directory()), left + 20, top + 128, 0xFF9BA8BC, false);
            if (SchematicCatalog.lastError() != null) {
                graphics.text(this.font, trim(SchematicCatalog.lastError(), 72), left + 20, top + 150, 0xFFFFB7B7, false);
            }
        } else if (!summary.readable()) {
            graphics.text(this.font, summary.displayName(), left + 20, top + 82, 0xFFFFFFFF, true);
            graphics.text(this.font, "Bu şema okunamadı:", left + 20, top + 106, 0xFFFF8E8E, false);
            graphics.text(this.font, trim(summary.error(), 72), left + 20, top + 126, 0xFFFFB7B7, false);
        } else {
            graphics.text(this.font, trim(summary.displayName(), 64), left + 20, top + 76, 0xFFFFFFFF, true);
            graphics.text(this.font,
                    "Boyut: " + summary.width() + " x " + summary.height() + " x " + summary.length(),
                    left + 20, top + 101, 0xFFD5DCE8, false);
            graphics.text(this.font,
                    "Yerleştirilecek blok: " + summary.blocks().size() + "  |  Bölge: " + summary.regions().size(),
                    left + 20, top + 120, 0xFFD5DCE8, false);

            int lineY = top + 151;
            for (int i = 0; i < Math.min(summary.regions().size(), 5); i++) {
                SchematicSummary.RegionSummary region = summary.regions().get(i);
                String line = region.name() + "  " + region.width() + "x" + region.height() + "x"
                        + region.length() + "  |  palet: " + region.paletteEntries();
                graphics.text(this.font, trim(line, 68), left + 20, lineY, 0xFF9FB4D0, false);
                lineY += 14;
            }
        }

        graphics.text(this.font, "Yerleştirme hızı (blok koyma / kırma)",
                left + 20, top + 211, 0xFFB8C1D1, false);
        graphics.text(this.font, "İkinci istemci  /  offline nick (3-16 karakter)",
                left + 20, top + 250, 0xFFB8C1D1, false);

        String buildState = BuilderSession.status();
        int stateColor = BuilderSession.isActive() ? 0xFF8FE3B0 : 0xFFFFC58A;
        graphics.text(this.font, "Durum: " + trim(buildState, 68), left + 20, bottom - 88, stateColor, true);
        graphics.text(this.font, "B: arayüz  |  H: oto inşaat + hareket aç/kapat",
                left + 20, bottom - 68, 0xFFB8C1D1, false);

        if (!launchStatus.isBlank()) {
            int statusColor = launchStatusSuccess ? 0xFF8FE3B0 : 0xFFFF9A9A;
            graphics.text(this.font, trim(launchStatus, 72), left + 20, bottom - 18, statusColor, false);
        }
    }

    @Override
    public void removed() {
        if (secondaryNickInput != null) {
            BuildSettings.setSecondaryAccountNickname(secondaryNickInput.getValue().trim());
        }
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
