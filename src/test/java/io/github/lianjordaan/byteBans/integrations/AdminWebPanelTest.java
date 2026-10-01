package io.github.lianjordaan.byteBans.integrations;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.database.Database;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.DatabaseUtils;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminWebPanelTest {
    @TempDir Path temp;

    @Test
    void localPanelRequiresTokenOriginAndCsrfAndAuditsActions() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        ByteBans plugin = mock(ByteBans.class);
        PunishmentsHandler handler = mock(PunishmentsHandler.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("admin_web.enabled", true);
        config.set("admin_web.port", port);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getPunishmentsHandler()).thenReturn(handler);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AdminWebPanelTest"));
        when(plugin.getServerName()).thenReturn("survival");
        PunishmentData row = new PunishmentData();
        row.setId(42);
        row.setType("ban");
        row.setSubjectType("PLAYER");
        row.setSubject(UUID.randomUUID().toString());
        row.setReason("<script>alert(1)</script>");
        row.setScope("*");
        row.setActive(true);
        when(handler.getPunishments()).thenReturn(Map.of(row.getId(), row));
        when(handler.punishSubject(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(true);
        when(handler.history(anyString(), anyString())).thenReturn(java.util.List.of(row));
        when(handler.deactivateSubject(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString())).thenReturn(new Result(true, "Removed ban #42."));

        try (AdminWebPanel ignored = AdminWebPanel.startIfEnabled(plugin)) {
            String base = "http://127.0.0.1:" + port;
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<String> anonymous = client.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, anonymous.statusCode());
            assertFalse(anonymous.body().contains("<script>alert(1)</script>"));
            assertTrue(anonymous.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));

            String token = Files.readString(temp.resolve("admin-web-token.txt")).trim();
            String login = "actor=Mod_1&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            assertEquals(403, post(client, base + "/login", login, null, null).statusCode());
            assertEquals(401, post(client, base + "/login", "actor=Mod_1&token=wrong", base, null).statusCode());
            HttpResponse<String> signedIn = post(client, base + "/login", login, base, null);
            assertEquals(303, signedIn.statusCode());
            String cookie = signedIn.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            assertTrue(signedIn.headers().firstValue("Set-Cookie").orElse("").contains("HttpOnly; SameSite=Strict"));
            HttpResponse<String> dashboard = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dashboard.statusCode());
            assertTrue(dashboard.body().contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
            assertFalse(dashboard.body().contains("<script>alert(1)</script>"));
            Matcher matcher = Pattern.compile("name='csrf' value='([^']+)'").matcher(dashboard.body());
            assertTrue(matcher.find());
            String csrf = matcher.group(1);
            String action = "action=ban&subject=" + row.getSubject() + "&scope=*&reason=Test";
            assertEquals(403, post(client, base + "/action", action, base, cookie).statusCode());
            assertEquals(403, post(client, base + "/action", action + "&csrf=" + csrf,
                    "http://evil.example", cookie).statusCode());
            assertEquals(200, post(client, base + "/action", action + "&csrf=" + csrf,
                    base, cookie).statusCode());
            verify(handler).punishSubject(eq(row.getSubject()), eq("PLAYER"), eq(row.getSubject()),
                    eq("WEB:Mod_1"), eq("ban"), eq("Test"), eq("*"), eq(0L), eq(true), eq(false));
            String kick = "action=kick&subject=" + row.getSubject() + "&scope=*&reason=Immediate+disconnect&csrf=" + csrf;
            assertEquals(200, post(client, base + "/action", kick, base, cookie).statusCode());
            verify(handler).punishSubject(eq(row.getSubject()), eq("PLAYER"), eq(row.getSubject()),
                    eq("WEB:Mod_1"), eq("kick"), eq("Immediate disconnect"), eq("*"), eq(0L), eq(false), eq(false));
            String undo = "action=unban&subject=" + row.getSubject()
                    + "&id=42&reason=Review&csrf=" + csrf;
            assertEquals(200, post(client, base + "/action", undo, base, cookie).statusCode());
            verify(handler).deactivateSubject("PLAYER", row.getSubject(), "ban", 42L,
                    "WEB:Mod_1", "Review", "unban");
            assertEquals(403, post(client, base + "/logout", "csrf=wrong", base, cookie).statusCode());
            assertEquals(200, post(client, base + "/logout", "csrf=" + csrf, base, cookie).statusCode());
            String signedOutPage = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(signedOutPage.contains("Access token"));
            assertFalse(signedOutPage.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        }
    }

    private static HttpResponse<String> post(HttpClient client, String url, String form, String origin, String cookie)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (origin != null) builder.header("Origin", origin);
        if (cookie != null) builder.header("Cookie", cookie);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void formRejectsDuplicateOrOversizedFieldsAndEscapesHtml() {
        assertThrows(IllegalArgumentException.class, () -> AdminWebPanel.parseForm("csrf=a&csrf=b"));
        assertThrows(IllegalArgumentException.class, () -> AdminWebPanel.parseForm("a=" + "x".repeat(8200)));
        assertEquals("&lt;&amp;&quot;&#39;&gt;", AdminWebPanel.esc("<&\"'>"));
        assertNull(AdminWebPanel.positiveId("-1"));
        assertEquals(42L, AdminWebPanel.positiveId("42"));
    }

    @Test
    void webIpBanAndUndoPersistThroughRealSqliteHandler() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseUtils.migrate(connection, "bytebans_", false);
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            ByteBans plugin = mock(ByteBans.class);
            Database database = mock(Database.class);
            when(plugin.getDatabase()).thenReturn(database);
            when(database.getConnection()).thenReturn(connection);
            when(plugin.getDatabaseTablePrefix()).thenReturn("bytebans_");
            when(plugin.getBBLogger()).thenReturn(mock(BBLogger.class));
            when(plugin.getServerName()).thenReturn("survival");
            when(plugin.getDataFolder()).thenReturn(temp.toFile());
            when(plugin.getLogger()).thenReturn(Logger.getLogger("AdminWebPanelSqliteTest"));
            when(plugin.isShuttingDown()).thenReturn(true); // no Bukkit server in this HTTP/database integration test
            YamlConfiguration config = new YamlConfiguration();
            config.set("admin_web.enabled", true);
            config.set("admin_web.port", port);
            when(plugin.getConfig()).thenReturn(config);
            PunishmentsHandler handler = new PunishmentsHandler(plugin);
            handler.loadPunishments();
            when(plugin.getPunishmentsHandler()).thenReturn(handler);
            try (AdminWebPanel ignored = AdminWebPanel.startIfEnabled(plugin)) {
                String base = "http://127.0.0.1:" + port;
                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
                String token = Files.readString(temp.resolve("admin-web-token.txt")).trim();
                HttpResponse<String> signIn = post(client, base + "/login", "actor=Mod_1&token=" + token,
                        base, null);
                assertEquals(303, signIn.statusCode());
                String cookie = signIn.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                String page = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                        .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
                Matcher matcher = Pattern.compile("name='csrf' value='([^']+)'").matcher(page);
                assertTrue(matcher.find());
                String csrf = matcher.group(1);
                String ban = "action=ipban&subject=203.0.113.7&scope=*&reason=abuse&csrf=" + csrf;
                assertEquals(200, post(client, base + "/action", ban, base, cookie).statusCode());
                PunishmentData active = handler.isIpBanned("203.0.113.7");
                assertNotNull(active);
                assertEquals("WEB:Mod_1", active.getPunisherUuid());
                String undo = "action=ipunban&subject=203.0.113.7&id=" + active.getId()
                        + "&reason=appeal&csrf=" + csrf;
                assertEquals(200, post(client, base + "/action", undo, base, cookie).statusCode());
                assertNull(handler.isIpBanned("203.0.113.7"));
                PunishmentsHandler reloaded = new PunishmentsHandler(plugin);
                reloaded.loadPunishments();
                assertEquals(2, reloaded.history("IP", "203.0.113.7").size());
                assertEquals("WEB:Mod_1", reloaded.history("IP", "203.0.113.7").getFirst().getPunisherUuid());
            }
        }
    }
}
