package com.rynie.eyyobuild.ui;

import com.rynie.eyyobuild.ProxySettings;
import com.rynie.eyyobuild.PublicProxyFinder;
import com.rynie.eyyobuild.Socks5ProxyTester;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Temporary SOCKS5 proxy editor for the next secondary Minecraft client. */
public final class EyyobuildProxyScreen extends Screen {
    private final Screen parent;
    private MultiLineEditBox hostInput;
    private MultiLineEditBox portInput;
    private Button testButton;
    private Button findButton;
    private boolean taskRunning;
    private String status = "";
    private boolean statusSuccess;

    public EyyobuildProxyScreen(Screen parent) {
        super(Component.literal("EYYOBUILD Proxy"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ProxySettings.Snapshot current = ProxySettings.snapshot();
        int center = this.width / 2;

        hostInput = MultiLineEditBox.builder()
                .setX(center - 188)
                .setY(this.height / 2 - 30)
                .setPlaceholder(Component.literal("proxy.example.com"))
                .setShowBackground(true)
                .setShowDecorations(false)
                .build(this.font, 215, 20, Component.literal("SOCKS5 proxy host"));
        hostInput.setLineLimit(1);
        hostInput.setCharacterLimit(253);
        if (current.enabled()) hostInput.setValue(current.host());
        addRenderableWidget(hostInput);

        portInput = MultiLineEditBox.builder()
                .setX(center + 34)
                .setY(this.height / 2 - 30)
                .setPlaceholder(Component.literal("1080"))
                .setShowBackground(true)
                .setShowDecorations(false)
                .build(this.font, 70, 20, Component.literal("Port"));
        portInput.setLineLimit(1);
        portInput.setCharacterLimit(5);
        portInput.setValue(Integer.toString(current.port()));
        addRenderableWidget(portInput);

        testButton = addRenderableWidget(Button.builder(
                Component.literal("Proxy Test Et"),
                button -> testProxy()
        ).bounds(center - 184, this.height / 2 + 5, 88, 20).build());

        findButton = addRenderableWidget(Button.builder(
                Component.literal("Otomatik Bul"),
                button -> findProxy()
        ).bounds(center - 92, this.height / 2 + 5, 98, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Kullan"), button -> {
            if (!ProxySettings.apply(hostInput.getValue(), portInput.getValue())) {
                status = "Host veya port geçersiz. Port 1-65535 olmalı.";
                statusSuccess = false;
                return;
            }
            Minecraft.getInstance().gui.setScreen(parent);
        }).bounds(center + 10, this.height / 2 + 5, 70, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Proxy Yok"), button -> {
            ProxySettings.clear();
            Minecraft.getInstance().gui.setScreen(parent);
        }).bounds(center + 84, this.height / 2 + 5, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Geri"), button -> {
            Minecraft.getInstance().gui.setScreen(parent);
        }).bounds(center - 44, this.height / 2 + 33, 88, 20).build());
    }

    private void testProxy() {
        if (taskRunning) return;
        String host = hostInput.getValue().trim();
        int port;
        try {
            port = Integer.parseInt(portInput.getValue().trim());
        } catch (NumberFormatException exception) {
            status = "Port 1-65535 arasında bir sayı olmalı.";
            statusSuccess = false;
            return;
        }

        if (!ProxySettings.isValidHost(host) || port < 1 || port > 65535) {
            status = "Geçerli bir proxy adresi ve port gir.";
            statusSuccess = false;
            return;
        }

        taskRunning = true;
        status = "SOCKS5 bağlantısı test ediliyor...";
        statusSuccess = true;
        updateTaskButtons();

        Thread.ofVirtual().name("EYYOBUILD-proxy-test").start(() -> {
            Socks5ProxyTester.TestResult result = Socks5ProxyTester.test(host, port);
            Minecraft.getInstance().execute(() -> {
                status = result.message();
                statusSuccess = result.success();
                taskRunning = false;
                updateTaskButtons();
            });
        });
    }

    private void findProxy() {
        if (taskRunning) return;
        taskRunning = true;
        status = "Proxy listeleri indiriliyor; adaylar test ediliyor...";
        statusSuccess = true;
        updateTaskButtons();

        Thread.ofVirtual().name("EYYOBUILD-proxy-discovery").start(() -> {
            PublicProxyFinder.FindResult result = PublicProxyFinder.findWorkingProxy();
            Minecraft.getInstance().execute(() -> {
                if (result.success()) {
                    hostInput.setValue(result.host());
                    portInput.setValue(Integer.toString(result.port()));
                    ProxySettings.apply(result.host(), Integer.toString(result.port()));
                    status = result.message() + " Kullanıma hazır.";
                    statusSuccess = true;
                } else {
                    status = result.message();
                    statusSuccess = false;
                }
                taskRunning = false;
                updateTaskButtons();
            });
        });
    }

    private void updateTaskButtons() {
        if (testButton != null) {
            testButton.active = !taskRunning;
            testButton.setMessage(Component.literal(taskRunning ? "Bekleyin..." : "Proxy Test Et"));
        }
        if (findButton != null) {
            findButton.active = !taskRunning;
            findButton.setMessage(Component.literal(taskRunning ? "Aranıyor..." : "Otomatik Bul"));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int panelWidth = 430;
        int panelHeight = 210;
        int left = this.width / 2 - panelWidth / 2;
        int top = this.height / 2 - panelHeight / 2;
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xE6121826);
        graphics.fill(left, top, left + panelWidth, top + 3, 0xFFB56BFF);
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.text(this.font, "İKİNCİ OYUN / PROXY", left + 18, top + 16, 0xFFFFFFFF, true);
        graphics.text(this.font, "Ayar yalnızca bundan sonra açılacak ikinci oyuna uygulanır.",
                left + 18, top + 38, 0xFFB8C1D1, false);
        graphics.text(this.font, "SOCKS5 host", left + 18, top + 62, 0xFFD5DCE8, false);
        graphics.text(this.font, "Port", left + 252, top + 62, 0xFFD5DCE8, false);
        graphics.text(this.font, "Otomatik Bul: internetteki açık proxy listelerini test eder.",
                left + 18, top + 96, 0xFFB8C1D1, false);
        graphics.text(this.font, "Uyarı: açık proxyler güvenilir değildir; hassas hesaplarla kullanma.",
                left + 18, top + 110, 0xFFFFC58A, false);

        if (!status.isBlank()) {
            int color = statusSuccess ? 0xFF8FE3B0 : 0xFFFF9A9A;
            graphics.text(this.font, trim(status, 64), left + 18, top + 145, color, false);
        }
    }

    private static String trim(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
