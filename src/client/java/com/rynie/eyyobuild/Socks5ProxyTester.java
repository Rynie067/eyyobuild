package com.rynie.eyyobuild;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Performs a SOCKS5 handshake and verifies that a CONNECT tunnel can be opened. */
public final class Socks5ProxyTester {
    private static final int TIMEOUT_MS = 6000;
    private static final String TEST_HOST = "example.com";
    private static final int TEST_PORT = 443;

    private Socks5ProxyTester() {}

    public static TestResult test(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());

            // Offer SOCKS5 with no authentication.
            output.write(new byte[] {0x05, 0x01, 0x00});
            output.flush();

            int version = input.readUnsignedByte();
            int method = input.readUnsignedByte();
            if (version != 0x05 || method != 0x00) {
                return new TestResult(false, "Proxy SOCKS5 anonim bağlantıyı kabul etmedi.");
            }

            byte[] target = TEST_HOST.getBytes(StandardCharsets.US_ASCII);
            output.writeByte(0x05);
            output.writeByte(0x01); // CONNECT
            output.writeByte(0x00);
            output.writeByte(0x03); // Domain name
            output.writeByte(target.length);
            output.write(target);
            output.writeShort(TEST_PORT);
            output.flush();

            int responseVersion = input.readUnsignedByte();
            int responseCode = input.readUnsignedByte();
            input.readUnsignedByte(); // RSV
            int addressType = input.readUnsignedByte();

            if (responseVersion != 0x05) {
                return new TestResult(false, "Proxy geçersiz SOCKS5 yanıtı verdi.");
            }

            switch (addressType) {
                case 0x01 -> input.skipNBytes(4);
                case 0x03 -> input.skipNBytes(input.readUnsignedByte());
                case 0x04 -> input.skipNBytes(16);
                default -> {
                    return new TestResult(false, "Proxy bilinmeyen adres türü döndürdü.");
                }
            }
            input.skipNBytes(2); // BND.PORT

            if (responseCode != 0x00) {
                return new TestResult(false, "Proxy tüneli açamadı (SOCKS5 kodu " + responseCode + ").");
            }
            return new TestResult(true, "Proxy çalışıyor; SOCKS5 tüneli başarıyla test edildi.");
        } catch (IOException | IllegalArgumentException exception) {
            String detail = exception.getMessage();
            if (detail == null || detail.isBlank()) detail = exception.getClass().getSimpleName();
            return new TestResult(false, "Proxy test edilemedi: " + detail);
        }
    }

    public record TestResult(boolean success, String message) {}
}
