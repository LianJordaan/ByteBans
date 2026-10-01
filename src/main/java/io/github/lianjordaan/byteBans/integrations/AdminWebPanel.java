package io.github.lianjordaan.byteBans.integrations;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.punishments.PunishmentsHandler;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import io.github.lianjordaan.byteBans.util.IpAddresses;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Optional local-only administration UI. Token holders have full moderation authority. */
public final class AdminWebPanel implements AutoCloseable {
    private static final int MAX_BODY = 8192;
    private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> CREATE = Map.ofEntries(
            Map.entry("ban", "ban"), Map.entry("mute", "mute"), Map.entry("kick", "kick"),
            Map.entry("ipban", "ipban"), Map.entry("ipmute", "ipmute"),
            Map.entry("warn", "warn"), Map.entry("note", "note"), Map.entry("freeze", "freeze"));
    private static final Map<String, String> UNDO = Map.ofEntries(
            Map.entry("unban", "ban"), Map.entry("unmute", "mute"),
            Map.entry("ipunban", "ipban"), Map.entry("ipunmute", "ipmute"),
            Map.entry("unwarn", "warn"), Map.entry("removenote", "note"),
            Map.entry("unfreeze", "freeze"));
    private static final Map<String, String> UNDO_FOR_TYPE = Map.ofEntries(
            Map.entry("ban", "unban"), Map.entry("mute", "unmute"),
            Map.entry("ipban", "ipunban"), Map.entry("ipmute", "ipunmute"),
            Map.entry("warn", "unwarn"), Map.entry("note", "removenote"),
            Map.entry("freeze", "unfreeze"));

    private final ByteBans plugin;
    private final PunishmentsHandler handler;
    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] token;
    private final int port;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> failedLogins = new ArrayDeque<>();

    private record Session(String actor, String csrf, long expiresAt) { }

    private AdminWebPanel(ByteBans plugin, HttpServer server, ExecutorService executor, byte[] token, int port) {
        this.plugin = plugin;
        this.handler = plugin.getPunishmentsHandler();
        this.server = server;
        this.executor = executor;
        this.token = token;
        this.port = port;
    }

    public static AdminWebPanel startIfEnabled(ByteBans plugin) throws IOException {
        if (!plugin.getConfig().getBoolean("admin_web.enabled", false)) return null;
        int port = plugin.getConfig().getInt("admin_web.port", 8765);
        if (port < 1024 || port > 65535) throw new IOException("admin_web.port must be 1024–65535");
        byte[] token = loadToken(plugin.getDataFolder().toPath());
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 16);
        ExecutorService executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task, "ByteBans admin web");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        AdminWebPanel panel = new AdminWebPanel(plugin, server, executor, token, port);
        server.createContext("/", panel::route);
        server.setExecutor(executor);
        server.start();
        plugin.getLogger().info("Local admin web panel listening on 127.0.0.1:" + port
                + "; token stored at plugins/ByteBans/admin-web-token.txt");
        return panel;
    }

    private static byte[] loadToken(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve("admin-web-token.txt");
        if (Files.isSymbolicLink(file)) throw new IOException("admin web token must not be a symbolic link");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } catch (UnsupportedOperationException ignored) {
                Files.createFile(file);
            }
            Files.writeString(file, randomToken() + "\n", StandardCharsets.UTF_8);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("admin web token is not a regular file");
        }
        String value = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (value.length() < 43 || value.length() > 128 || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("admin web token is invalid; it must be a 32-byte URL-safe secret");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            applySecurityHeaders(exchange);
            if (!allowedHost(exchange)) {
                text(exchange, 421, "Use http://127.0.0.1:" + port + " on the server or through a local tunnel.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/style.css") && exchange.getRequestMethod().equals("GET")) {
                byte[] css;
                try (var stream = getClass().getResourceAsStream("/admin-web.css")) {
                    if (stream == null) throw new IOException("Admin web stylesheet missing");
                    css = stream.readAllBytes();
                }
                send(exchange, 200, "text/css; charset=utf-8", css);
                return;
            }
            if (path.equals("/login") && exchange.getRequestMethod().equals("POST")) {
                if (!validOrigin(exchange)) { text(exchange, 403, "Origin rejected."); return; }
                login(exchange);
                return;
            }
            Session session = session(exchange);
            if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
                if (session == null) html(exchange, 200, loginPage(""));
                else dashboard(exchange, session);
                return;
            }
            if (session == null) { text(exchange, 401, "Sign in first."); return; }
            if (path.equals("/action") && exchange.getRequestMethod().equals("POST")) {
                if (!validOrigin(exchange)) { text(exchange, 403, "Origin rejected."); return; }
                Map<String, String> form = form(exchange);
                if (!sameSecret(session.csrf(), form.get("csrf"))) {
                    text(exchange, 403, "Form token expired. Reload the page."); return;
                }
                String message;
                try {
                    message = perform(form, session);
                } catch (IllegalArgumentException error) {
                    html(exchange, 400, resultPage(session, "Could not save", error.getMessage()));
                    return;
                }
                html(exchange, 200, resultPage(session, "Saved", message));
                return;
            }
            if (path.equals("/logout") && exchange.getRequestMethod().equals("POST")) {
                if (!validOrigin(exchange)) { text(exchange, 403, "Origin rejected."); return; }
                Map<String, String> form = form(exchange);
                if (!sameSecret(session.csrf(), form.get("csrf"))) { text(exchange, 403, "Form token expired."); return; }
                sessions.remove(cookie(exchange));
                exchange.getResponseHeaders().set("Set-Cookie", "bb_session=; Max-Age=0; HttpOnly; SameSite=Strict; Path=/");
                html(exchange, 200, loginPage("Signed out."));
                return;
            }
            text(exchange, 404, "Page not found.");
        } catch (IllegalArgumentException error) {
            text(exchange, 400, "Invalid request.");
        } catch (Exception error) {
            plugin.getLogger().warning("Admin web request failed: " + error.getClass().getSimpleName());
            text(exchange, 500, "Request failed. Check the server log.");
        } finally {
            exchange.close();
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(exchange);
        String actor = form.getOrDefault("actor", "");
        String candidate = form.getOrDefault("token", "");
        if (loginLimited()) { text(exchange, 429, "Too many attempts. Try again in one minute."); return; }
        if (!ACTOR.matcher(actor).matches() || !sameSecret(token, candidate)) {
            markFailedLogin();
            html(exchange, 401, loginPage("Invalid token or admin name."));
            return;
        }
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        if (sessions.size() >= 64) { text(exchange, 503, "Too many active sessions."); return; }
        String id = randomToken();
        sessions.put(id, new Session(actor, randomToken(), System.currentTimeMillis() + Duration.ofHours(1).toMillis()));
        exchange.getResponseHeaders().set("Set-Cookie", "bb_session=" + id + "; HttpOnly; SameSite=Strict; Path=/");
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(303, -1);
    }

    private boolean loginLimited() {
        synchronized (failedLogins) {
            long cutoff = System.currentTimeMillis() - 60_000;
            while (!failedLogins.isEmpty() && failedLogins.peekFirst() < cutoff) failedLogins.removeFirst();
            return failedLogins.size() >= 5;
        }
    }

    private void markFailedLogin() {
        synchronized (failedLogins) { failedLogins.addLast(System.currentTimeMillis()); }
    }

    private Session session(HttpExchange exchange) {
        String id = cookie(exchange);
        if (id == null) return null;
        Session value = sessions.get(id);
        if (value == null) return null;
        if (value.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(id);
            return null;
        }
        return value;
    }

    private static String cookie(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null || header.length() > 4096) return null;
        for (String part : header.split(";")) {
            String value = part.trim();
            if (value.startsWith("bb_session=")) return value.substring("bb_session=".length());
        }
        return null;
    }

    private boolean allowedHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        return ("127.0.0.1:" + port).equalsIgnoreCase(host)
                || ("localhost:" + port).equalsIgnoreCase(host);
    }

    private boolean validOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return ("http://127.0.0.1:" + port).equalsIgnoreCase(origin)
                || ("http://localhost:" + port).equalsIgnoreCase(origin);
    }

    static Map<String, String> parseForm(String raw) {
        if (raw.length() > MAX_BODY) throw new IllegalArgumentException("Form is too large");
        Map<String, String> fields = new HashMap<>();
        if (raw.isEmpty()) return fields;
        for (String entry : raw.split("&", -1)) {
            String[] pair = entry.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8);
            if (key.length() > 40 || value.length() > 2000 || fields.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate or oversized form field");
            }
        }
        return fields;
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            throw new IllegalArgumentException("Use a browser form");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new IllegalArgumentException("Form is too large");
        return parseForm(new String(body, StandardCharsets.UTF_8));
    }

    private String perform(Map<String, String> form, Session session) throws Exception {
        String action = form.getOrDefault("action", "").toLowerCase(Locale.ROOT);
        String type = CREATE.getOrDefault(action, UNDO.get(action));
        if (type == null) throw new IllegalArgumentException("Unknown moderation action");
        boolean ip = type.startsWith("ip");
        String subject = resolveSubject(form.getOrDefault("subject", ""), ip);
        String reason = form.getOrDefault("reason", "").trim();
        if (reason.isEmpty() || reason.length() > 500) throw new IllegalArgumentException("Reason must be 1–500 characters");
        String actor = "WEB:" + session.actor();
        if (UNDO.containsKey(action)) {
            Long id = positiveId(form.get("id"));
            if (id == null) throw new IllegalArgumentException("Choose a history record to undo");
            Result result = handler.deactivateSubject(ip ? "IP" : "PLAYER", subject, type, id, actor, reason, action);
            if (!result.isSuccess()) throw new IllegalArgumentException(result.getMessage());
            return result.getMessage() + " Audit actor: " + actor + ".";
        }
        String scope = form.getOrDefault("scope", "*").trim();
        if (scope.isEmpty() || scope.length() > 64 || !scope.matches("[A-Za-z0-9_.*,?-]+")) {
            throw new IllegalArgumentException("Scope must be 1–64 server-name, wildcard or comma characters");
        }
        long duration = 0;
        String durationText = form.getOrDefault("duration", "").trim();
        if (!durationText.isEmpty()) {
            if (type.equals("note") || type.equals("kick")) {
                throw new IllegalArgumentException("Notes and kicks cannot have a duration");
            }
            try {
                duration = CommandUtils.parseToMillis(durationText);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Duration must be positive, such as 30m or 7d");
            }
            if (duration <= 0 || duration > Duration.ofDays(3650).toMillis()) {
                throw new IllegalArgumentException("Duration must be at most ten years");
            }
        }
        String subjectType = ip ? "IP" : "PLAYER";
        if (!type.equals("warn") && !type.equals("note") && !type.equals("kick")
                && handler.findActiveSubjectInScope(subjectType, subject, type, scope) != null) {
            throw new IllegalArgumentException("That action is already active for this target and scope");
        }
        boolean persistent = !type.equals("kick");
        if (!handler.punishSubject(subject, subjectType, subject, actor, type, reason, scope,
                duration, persistent, false)) {
            throw new IllegalArgumentException("Database rejected the action. Check the server log");
        }
        PunishmentData latest = handler.history(subjectType, subject).stream()
                .filter(row -> row.getType().equalsIgnoreCase(type)).findFirst().orElse(null);
        return "Saved " + action + (latest == null ? "" : " #" + latest.getId()) + " for " + subject
                + ". Audit actor: " + actor + ".";
    }

    private String resolveSubject(String target, boolean ip) throws Exception {
        String value = target.trim();
        if (value.length() > 80) throw new IllegalArgumentException("Target is too long");
        if (ip && value.startsWith("ip:")) value = value.substring(3);
        if (ip && !value.startsWith("user:")) return IpAddresses.canonical(value);
        if (value.startsWith("user:")) value = value.substring(5);
        String player = resolvePlayer(value);
        if (!ip) return player;
        String address = handler.lastPlayerAddress(player);
        if (address == null) throw new IllegalArgumentException("That player has no recorded address");
        return address;
    }

    private String resolvePlayer(String value) throws Exception {
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException ignored) { /* Search known names on the server thread below. */ }
        if (!ACTOR.matcher(value).matches()) throw new IllegalArgumentException("Use a known player name or UUID");
        String name = value;
        String uuid = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                if (name.equalsIgnoreCase(player.getName())) return player.getUniqueId().toString();
            }
            return null;
        }).get(5, TimeUnit.SECONDS);
        if (uuid == null) throw new IllegalArgumentException("Player has not joined this server; use a UUID");
        return uuid;
    }

    static Long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,17}")) return null;
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private void dashboard(HttpExchange exchange, Session session) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && query.length() > 512) { text(exchange, 400, "Search is too long."); return; }
        String search = query == null ? "" : parseForm(query).getOrDefault("q", "").trim();
        if (search.length() > 100) { text(exchange, 400, "Search is too long."); return; }
        String match = search.toLowerCase(Locale.ROOT);
        List<PunishmentData> rows = new ArrayList<>(handler.getPunishments().values());
        rows.sort(Comparator.comparingLong(PunishmentData::getId).reversed());
        long active = rows.stream().filter(PunishmentData::isActive).count();
        StringBuilder body = new StringBuilder(pageStart("Overview"));
        body.append("<header><div><p class='eyebrow'>LOCAL ADMIN CONSOLE</p><h1>ByteBans</h1><p class='sub'>Moderation, with an audit trail.</p></div>")
                .append("<form method='post' action='/logout'><input type='hidden' name='csrf' value='").append(esc(session.csrf()))
                .append("'><span class='actor'>").append(esc(session.actor())).append("</span><button class='quiet'>Sign out</button></form></header>")
                .append("<div class='stats'><article><b>").append(rows.size()).append("</b><span>Records</span></article><article><b>")
                .append(active).append("</b><span>Active</span></article><article><b>")
                .append(esc(plugin.getServerName())).append("</b><span>Server scope</span></article></div>")
                .append("<section class='card'><div class='section-head'><div><h2>New action</h2><p>Only people with the local web token can submit.</p></div></div>")
                .append("<form method='post' action='/action' class='action-form'><input type='hidden' name='csrf' value='")
                .append(esc(session.csrf())).append("'><label>Action<select name='action'>");
        for (String action : new String[]{"ban", "mute", "kick", "ipban", "ipmute", "warn", "note", "freeze"}) {
            body.append("<option value='").append(action).append("'>").append(action.toUpperCase(Locale.ROOT)).append("</option>");
        }
        body.append("</select></label><label>Target<input name='subject' required maxlength='80' placeholder='Player name, UUID, or IP address'></label>")
                .append("<label>Scope<input name='scope' maxlength='64' value='*'></label>")
                .append("<label>Duration <span class='hint'>optional</span><input name='duration' maxlength='24' placeholder='e.g. 1d'></label>")
                .append("<label class='wide'>Reason<input name='reason' required maxlength='500' placeholder='What happened?'></label>")
                .append("<button class='primary'>Save action</button></form></section>")
                .append("<section class='card'><div class='section-head'><div><h2>History</h2><p>Newest records first. Showing up to 100.</p></div>")
                .append("<form method='get' action='/' class='search'><input name='q' maxlength='100' value='")
                .append(esc(search)).append("' placeholder='Search records'><button>Search</button></form></div>")
                .append("<div class='table-wrap'><table><thead><tr><th>ID</th><th>Action</th><th>Target</th><th>Reason</th><th>Scope</th><th>When</th><th>Status</th><th></th></tr></thead><tbody>");
        int shown = 0;
        for (PunishmentData row : rows) {
            if (!match.isEmpty() && !(String.valueOf(row.getId()) + " " + row.getType() + " "
                    + row.getSubject() + " " + row.getReason() + " " + row.getScope()).toLowerCase(Locale.ROOT).contains(match)) {
                continue;
            }
            if (shown++ >= 100) break;
            body.append("<tr><td>#").append(row.getId()).append("</td><td><strong>").append(esc(row.getType()))
                    .append("</strong></td><td><small>").append(esc(row.getSubjectType())).append("</small><br>")
                    .append(esc(row.getSubject())).append("</td><td>").append(esc(row.getReason())).append("</td><td>")
                    .append(esc(row.getScope())).append("</td><td>").append(DATE.format(Instant.ofEpochMilli(row.getStartTime())))
                    .append("</td><td><span class='pill ").append(row.isActive() ? "on'>Active" : "off'>Closed")
                    .append("</span></td><td>");
            String undo = UNDO_FOR_TYPE.get(row.getType().toLowerCase(Locale.ROOT));
            if (row.isActive() && undo != null) {
                body.append("<form method='post' action='/action' class='undo'><input type='hidden' name='csrf' value='")
                        .append(esc(session.csrf())).append("'><input type='hidden' name='action' value='").append(undo)
                        .append("'><input type='hidden' name='subject' value='").append(esc(row.getSubject()))
                        .append("'><input type='hidden' name='id' value='").append(row.getId())
                        .append("'><input name='reason' required maxlength='500' aria-label='Reason to undo record ")
                        .append(row.getId()).append("' placeholder='Undo reason'>")
                        .append("<button aria-label='Undo record ").append(row.getId()).append("'>Undo</button></form>");
            }
            body.append("</td></tr>");
        }
        if (shown == 0) body.append("<tr><td colspan='8' class='empty'>No matching records.</td></tr>");
        body.append("</tbody></table></div></section>").append(pageEnd());
        html(exchange, 200, body.toString());
    }

    private String loginPage(String notice) {
        return pageStart("Sign in") + "<main class='login card'><p class='eyebrow'>LOCAL ADMIN CONSOLE</p>"
                + "<h1>ByteBans</h1><p class='sub'>Sign in with the token stored on the Minecraft server.</p>"
                + (notice.isEmpty() ? "" : "<p class='notice'>" + esc(notice) + "</p>")
                + "<form method='post' action='/login'><label>Admin name<input name='actor' required minlength='3' maxlength='16' autocomplete='username'></label>"
                + "<label>Access token<input name='token' type='password' required autocomplete='current-password'></label>"
                + "<button class='primary'>Sign in</button></form><p class='hint'>Bound to 127.0.0.1. Use a local SSH tunnel for remote access.</p></main>"
                + pageEnd();
    }

    private String resultPage(Session session, String title, String message) {
        return pageStart(title) + "<main class='login card'><p class='eyebrow'>MODERATION RESULT</p><h1>"
                + esc(title) + "</h1><p>" + esc(message) + "</p><a class='back' href='/'>Back to records</a></main>" + pageEnd();
    }

    private static String pageStart(String title) {
        return "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + esc(title) + " · ByteBans</title><link rel='stylesheet' href='/style.css'></head><body><div class='shell'>";
    }

    private static String pageEnd() { return "</div></body></html>"; }

    static String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static boolean sameSecret(String expected, String actual) {
        return actual != null && sameSecret(expected.getBytes(StandardCharsets.UTF_8), actual);
    }

    private static boolean sameSecret(byte[] expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void applySecurityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy", "default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none'");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
    }

    private static void html(HttpExchange exchange, int status, String content) throws IOException {
        send(exchange, status, "text/html; charset=utf-8", content.getBytes(StandardCharsets.UTF_8));
    }

    private static void text(HttpExchange exchange, int status, String content) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", content.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        sessions.clear();
    }
}
