package io.github.lianjordaan.byteBans.integrations;

import io.github.lianjordaan.byteBans.model.PunishmentData;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Optional, outbound-only moderation audit messages. No Discord command channel exists. */
public final class DiscordWebhookNotifier implements AutoCloseable {
    private final URI endpoint;
    private final Logger logger;
    private final HttpClient client;
    private final ThreadPoolExecutor worker;
    private final boolean includeNotes;

    // Package visibility lets tests use a local HTTP receiver; production construction validates Discord's host.
    DiscordWebhookNotifier(URI endpoint, boolean includeNotes, Logger logger) {
        this.endpoint = endpoint;
        this.includeNotes = includeNotes;
        this.logger = logger;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), task -> {
                    Thread thread = new Thread(task, "ByteBans Discord webhook");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public static DiscordWebhookNotifier fromConfig(FileConfiguration config, Logger logger) {
        if (!config.getBoolean("discord.enabled", false)) return null;
        try {
            URI endpoint = validateEndpoint(config.getString("discord.webhook_url", ""));
            return new DiscordWebhookNotifier(endpoint, config.getBoolean("discord.include_notes", false), logger);
        } catch (IllegalArgumentException error) {
            logger.warning("Discord webhook is disabled: " + error.getMessage());
            return null;
        }
    }

    static URI validateEndpoint(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("set discord.webhook_url first");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid Discord webhook URL");
        }
        String path = uri.getRawPath();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"discord.com".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || path == null
                || !path.matches("/api(?:/v[0-9]+)?/webhooks/[0-9]+/[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("use an official https://discord.com/api/webhooks/... URL");
        }
        return uri;
    }

    public void record(PunishmentData punishment) {
        if (!includeNotes && ("note".equalsIgnoreCase(punishment.getType())
                || "unnote".equalsIgnoreCase(punishment.getType())
                || "removenote".equalsIgnoreCase(punishment.getType()))) return;
        String content = content(punishment);
        try {
            worker.execute(() -> deliver(content));
        } catch (RejectedExecutionException error) {
            logger.warning("Discord webhook queue is full or shutting down; moderation record #"
                    + punishment.getId() + " remains saved in the database");
        }
    }

    static String content(PunishmentData punishment) {
        String subject = "IP".equalsIgnoreCase(punishment.getSubjectType())
                ? "IP address " + punishment.getSubject() : "player " + punishment.getSubject();
        String message = "ByteBans #" + punishment.getId() + " · " + punishment.getType().toUpperCase(Locale.ROOT)
                + " · " + subject + " · scope " + punishment.getScope() + " · by "
                + punishment.getPunisherUuid() + "\nReason: " + punishment.getReason();
        message = message.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
        return message.length() <= 1900 ? message : message.substring(0, 1899) + "…";
    }

    private void deliver(String content) {
        String payload = "{\"content\":\"" + jsonEscape(content)
                + "\",\"allowed_mentions\":{\"parse\":[]}}";
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "ByteBans/1.1")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 300) return;
                if (status != 429 && status < 500) {
                    logger.warning("Discord webhook rejected a moderation update (HTTP " + status + ")");
                    return;
                }
                long delay = status == 429 ? retryAfterMillis(response.headers().firstValue("Retry-After").orElse(""))
                        : 1000L * (attempt + 1);
                Thread.sleep(delay);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                // Avoid logging the exception: some HTTP errors include the secret URL.
                if (attempt == 2) logger.warning("Discord webhook delivery failed; moderation remains saved locally");
            }
        }
        logger.warning("Discord webhook delivery did not succeed after three attempts");
    }

    static long retryAfterMillis(String header) {
        try {
            double seconds = Double.parseDouble(header);
            if (Double.isFinite(seconds) && seconds >= 0) {
                return Math.max(250, Math.min(10_000, (long) (seconds * 1000)));
            }
        } catch (NumberFormatException ignored) {
            // Bounded fallback below.
        }
        return 1000;
    }

    static String jsonEscape(String text) {
        StringBuilder result = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.toString();
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
