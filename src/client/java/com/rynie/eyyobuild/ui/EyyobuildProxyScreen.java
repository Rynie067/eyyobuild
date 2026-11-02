package com.rynie.eyyobuild.ui;

import com.rynie.eyyobuild.ProxySettings;
import com.rynie.eyyobuild.Socks5ProxyTester;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Temporary SOCKS5 proxy editor for the secondary Minecraft client only. */
public final class EyyobuildProxyScreen extends Screen {
    private final Screen parent;
    private MultiLineEditBox hostInput;
    private MultiLineEditBox portInput;
    private Button testButton;
    private boolean testRunning;
    private String testStatus = "";
    private boolean testSuccess;

    public EyyobuildProxyScreen(Screen parent) {
        super(Component.literal("EYYOBUILD Proxy"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ProxySettings.Snapshot current = ProxySettings.snapshot();
        int center = this.width / 2;

        hostInput = MultiLineEditBox.builder()
                .setX(center - 150)
                .setY(this.height / 2 - 28)
                .setPlaceholder(Component.literal("proxy.example.com"))
                .setShowBackground(true)
                .setShowDecorations(false)
                .build(this.font, 220, 20, Component.literal("SOCKS5 proxy host"));
        hostInput.setLineLimit(1);
        hostInput.setCharacterLimit(253);
        if (current.enabled()) hostInput.setValue(current.host());
        addRenderableWidget(hostInput);

        portInput = MultiLineEditBox.builder()
                .setX(center + 78)
                .setY(this.height / 2 - 28)
                .setPlaceholder(Component.literal("1080"))
                .setShowBackground(true)
                .setShowDecorations(false)
                .build(this.font, 72, 20, Component.literal("Port"));
        portInput.setLineLimit(1);
        portInput.setCharacterLimit(5);
        portInput.setValue(Integer.toString(current.port()));
        addRenderableWidget(portInput);

        testButton = addRenderableWidget(Button.builder(
                Component.literal("Proxy Test Et"),
                button -> testProxy()
        ).bounds(center - 150, this.height / 2 + 12, 104, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Kullan"), button -> {
            if (!ProxySettings.apply(hostInput.getValue(), portInput.getValue())) {
                testStatus = "Host veya port geçersiz. Port 1-65535 olmalı.";
                testSuccess = false;
                return;
            }
            Minecraft.getInstance().setScreen(parent);
        }).bounds(center - 40, this.height / 2 + 12, 82, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Proxy Yok"), button -> {
            ProxySettings.clear();
            Minecraft.getInstance().setScreen(parent);
        }).bounds(center + 48, this.height / 2 + 12, 102, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Geri"), button -> {
            Minecraft.getInstance().setScreen(parent);
        }).bounds(center - 44, this.height / 2 + 44, 88, 20).build());
    }

    private void testProxy() {
        if (testRunning) return;
        String host = hostInput.getValue().trim();
        int port;
        try {
            port = Integer.parseInt(portInput.getValue().trim());
        } catch (NumberFormatException exception) {
            testStatus = "Port 1-65535 arasında bir sayı olmalı.";
            testSuccess = false;
            return;
        }

        if (host.isBlank() || port < 1 || port > 65535) {
            testStatus = "Geçerli bir proxy adresi ve port gir.";
            testSuccess = false;
            return;
        }

        testRunning = true;
        testStatus = "SOCKS5 bağlantısı test ediliyor...";
        testSuccess = true;
        if (testButton != null) testButton.setMessage(Component.literal("Test ediliyor..."));

        Thread.ofVirtual().name("EYYOBUILD-proxy-test").start(() -> {
            Socks5ProxyTester.TestResult result = Socks5ProxyTester.test(host, port);
            Minecraft.getInstance().execute(() -> {
                testStatus = result.message();
                testSuccess = result.success();
                testRunning = false;
                if (testButton != null) testButton.setMessage(Component.literal("Proxy Test Et"));
            });
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int panelWidth = 360;
        int panelHeight = 190;
        int left = this.width / 2 - panelWidth / 2;
        int top = this.height / 2 - panelHeight / 2;
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xE6121826);
        graphics.fill(left, top, left + panelWidth, top + 3, 0xFFB56BFF);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(this.font, "İKİNCİ OYUN / PROXY", left + 18, top + 16, 0xFFFFFFFF, true);
        graphics.text(this.font, "Yalnızca ikinci Minecraft penceresini etkiler.", left + 18, top + 38,
                0xFFB8C1D1, false);
        graphics.text(this.font, "SOCKS5 host", left + 18, top + 66, 0xFFD5DCE8, false);
        graphics.text(this.font, "Port", left + 248, top + 66, 0xFFD5DCE8, false);
        graphics.text(this.font, "Proxy bilgisi bellekte tutulur; oyun yeniden açılınca sıfırlanır.",
                left + 18, top + 112, 0xFF9BA8BC, false);

        if (!testStatus.isBlank()) {
            int color = testSuccess ? 0xFF8FE3B0 : 0xFFFF9A9A;
            graphics.text(this.font, trim(testStatus, 58), left + 18, top + 145, color, false);
        }
    }

    private static String trim(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
