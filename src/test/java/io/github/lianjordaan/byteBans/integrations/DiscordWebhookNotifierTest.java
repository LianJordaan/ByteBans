package io.github.lianjordaan.byteBans.integrations;

import com.sun.net.httpserver.HttpServer;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DiscordWebhookNotifierTest {
    @Test
    void acceptsOnlyOfficialHttpsWebhookDestination() {
        assertEquals("discord.com", DiscordWebhookNotifier.validateEndpoint(
                "https://discord.com/api/webhooks/1234/abcd_EFG-5678").getHost());
        for (String invalid : new String[]{"http://discord.com/api/webhooks/1234/token",
                "https://discord.com.evil.example/api/webhooks/1234/token",
                "https://discord.com:444/api/webhooks/1234/token",
                "https://discord.com/api/webhooks/1234/token?wait=true",
                "https://discord.com/api/webhooks/1234/token/../../other",
                "https://example.com/api/webhooks/1234/token"}) {
            assertThrows(IllegalArgumentException.class, () -> DiscordWebhookNotifier.validateEndpoint(invalid), invalid);
        }
    }

    @Test
    void messageIsBoundedAndJsonEscapedWithoutDroppingAuditIdentity() {
        PunishmentData row = new PunishmentData();
        row.setId(42);
        row.setType("ipban");
        row.setSubjectType("IP");
        row.setSubject("203.0.113.7");
        row.setPunisherUuid("CONSOLE");
        row.setScope("*");
        row.setReason("@everyone \"quoted\" \\ newline\n" + "x".repeat(2500));
        String content = DiscordWebhookNotifier.content(row);
        assertTrue(content.startsWith("ByteBans #42 · IPBAN · IP address 203.0.113.7"));
        assertTrue(content.length() <= 1900);
        String json = DiscordWebhookNotifier.jsonEscape(content);
        assertTrue(json.contains("\\\"quoted\\\""));
        assertTrue(json.contains("\\\\"));
        assertFalse(json.contains("\n"));
    }

    @Test
    void retryAfterIsBounded() {
        assertEquals(250, DiscordWebhookNotifier.retryAfterMillis("0"));
        assertEquals(1500, DiscordWebhookNotifier.retryAfterMillis("1.5"));
        assertEquals(10_000, DiscordWebhookNotifier.retryAfterMillis("9999"));
        assertEquals(1000, DiscordWebhookNotifier.retryAfterMillis("bad"));
    }

    @Test
    void sendsBoundedPayloadAndRetriesRateLimitWithoutBlockingModeration() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        receiver.createContext("/test", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean success = attempts.incrementAndGet() != 1;
            if (!success) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
            if (success) delivered.countDown();
        });
        receiver.start();
        try (DiscordWebhookNotifier notifier = new DiscordWebhookNotifier(
                URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/test"),
                false, Logger.getLogger("DiscordWebhookNotifierTest"))) {
            PunishmentData row = new PunishmentData();
            row.setId(42);
            row.setType("ban");
            row.setSubjectType("PLAYER");
            row.setSubject("player-id");
            row.setPunisherUuid("WEB:Mod_1");
            row.setScope("*");
            row.setReason("@everyone test");
            notifier.record(row);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertTrue(body.get().contains("\"allowed_mentions\":{\"parse\":[]}"));
            assertTrue(body.get().contains("@everyone test"));
        } finally {
            receiver.stop(0);
        }
    }
}
