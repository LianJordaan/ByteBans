package io.github.lianjordaan.byteBans.util;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.Result;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandUtils {

    /**
     * Hybrid parser:
     * - If validKeys are supplied → only those keys start new key:value pairs
     * - If no validKeys are supplied → fall back to legacy behavior
     */
    public static Map<String, String> parseArgs(String[] args, String... validKeys) {
        if (validKeys == null || validKeys.length == 0) {
            return parseLegacy(args);
        }
        return parseWithKnownKeys(args, validKeys);
    }

    /**
     * Legacy behavior (best-effort parsing).
     * First unescaped colon starts a key, everything else is value.
     */
    private static Map<String, String> parseLegacy(String[] args) {
        Map<String, String> map = new HashMap<>();
        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();

        for (String arg : args) {
            int colonIndex = findUnescapedColon(arg);

            if (colonIndex > 0) { // key must have at least 1 char
                // Save previous key-value
                if (currentKey != null) {
                    map.put(currentKey, unescape(currentValue.toString().trim()));
                }

                currentKey = arg.substring(0, colonIndex).toLowerCase();
                currentValue = new StringBuilder(arg.substring(colonIndex + 1));
            } else {
                if (currentKey != null) {
                    currentValue.append(" ").append(arg);
                }
            }
        }

        if (currentKey != null) {
            map.put(currentKey, unescape(currentValue.toString().trim()));
        }

        return map;
    }

    /**
     * Context-aware parsing using known keys.
     */
    private static Map<String, String> parseWithKnownKeys(String[] args, String... validKeys) {
        Map<String, String> map = new HashMap<>();
        Set<String> keys = new HashSet<>();

        for (String key : validKeys) {
            keys.add(key.toLowerCase());
        }

        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();

        for (String arg : args) {
            int colonIndex = findUnescapedColon(arg);

            if (colonIndex > 0) {
                String possibleKey = arg.substring(0, colonIndex).toLowerCase();

                if (keys.contains(possibleKey)) {
                    if (currentKey != null) {
                        map.put(currentKey, currentValue.toString().trim());
                    }

                    currentKey = possibleKey;
                    currentValue = new StringBuilder(arg.substring(colonIndex + 1));
                    continue;
                }
            }

            if (currentKey != null) {
                currentValue.append(" ").append(arg);
            }
        }

        if (currentKey != null) {
            map.put(currentKey, currentValue.toString().trim());
        }

        return map;
    }

    /**
     * Finds the first unescaped ':' in a string.
     */
    private static int findUnescapedColon(String s) {
        boolean escaped = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == ':') {
                out.append(':');
                i++; // skip next char
            } else {
                out.append(c);
            }
        }

        return out.toString();
    }

    public static boolean hasFlag(String flag, String[] args) {
        // Detect and remove "-s" anywhere in args
        for (String arg : args) {
            if (arg.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }

    public static Result getUuidFromUsername(String username) {
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && player.getName().equalsIgnoreCase(username)) {
                return new Result(true, player.getUniqueId().toString());
            }
        }
        return new Result(false, "No player found with that username.");
    }

    public static Result getUsernameFromUuid(String uuid) {
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getUniqueId().toString().equalsIgnoreCase(uuid)) {
                return new Result(true, player.getName());
            }
        }
        return new Result(false, "No player found with that UUID.");
    }

    /**
     * Parses a time string like "1h", "30m", "2.5d"
     * @return duration in milliseconds
     * @throws IllegalArgumentException if invalid
     */
    public static long parseToMillis(String input) {
        final Pattern PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)(s|m|h|d|w|mo|y)$", Pattern.CASE_INSENSITIVE);

        // seconds per unit
        final double MINUTE = 60;
        final double HOUR = 60 * MINUTE;
        final double DAY = 24 * HOUR;
        final double WEEK = 7 * DAY;
        final double YEAR = 365.25 * DAY;
        final double MONTH = YEAR / 12.0;

        Matcher matcher = PATTERN.matcher(input.trim());

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid time format: " + input);
        }

        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toLowerCase();

        double seconds = switch (unit) {
            case "s" -> value;
            case "m" -> value * MINUTE;
            case "h" -> value * HOUR;
            case "d" -> value * DAY;
            case "w" -> value * WEEK;
            case "mo" -> value * MONTH;
            case "y" -> value * YEAR;
            default -> throw new IllegalStateException("Unhandled unit: " + unit);
        };

        return (long) (seconds * 1000);
    }

    /**
     * Breaks milliseconds into days, hours, minutes, seconds.
     * Rounded to the closest second.
     *
     * @param millis duration in milliseconds
     * @return ordered map of unit -> value (d, h, m, s)
     */
    public static Map<String, Long> toUnitMap(long millis) {
        Map<String, Long> result = new LinkedHashMap<>();

        if (millis <= 0) {
            result.put("s", 0L);
            return result;
        }

        // round to nearest second
        long totalSeconds = Math.round(millis / 1000.0);

        long days = totalSeconds / 86_400;
        totalSeconds %= 86_400;

        long hours = totalSeconds / 3_600;
        totalSeconds %= 3_600;

        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        if (days > 0) {
            result.put("d", days);
        }
        if (hours > 0) {
            result.put("h", hours);
        }
        if (minutes > 0) {
            result.put("m", minutes);
        }
        if (seconds > 0 || result.isEmpty()) {
            result.put("s", seconds);
        }

        return result;
    }

    /**
     * Formats milliseconds into a human-readable natural language string.
     * Examples:
     *   432000000 -> "5 days"
     *   439200000 -> "5 days and 1 hour"
     *   439260000 -> "5 days, 1 hour and 1 minute"
     *
     * @param millis duration in milliseconds
     * @return formatted string
     */
    public static String formatDurationNatural(long millis) {
        Map<String, Long> units = toUnitMap(millis);
        List<String> parts = new ArrayList<>();

        for (Map.Entry<String, Long> entry : units.entrySet()) {
            long value = entry.getValue();
            if (value <= 0) continue;

            String unitName;
            switch (entry.getKey()) {
                case "d" -> unitName = value == 1 ? "day" : "days";
                case "h" -> unitName = value == 1 ? "hour" : "hours";
                case "m" -> unitName = value == 1 ? "minute" : "minutes";
                case "s" -> unitName = value == 1 ? "second" : "seconds";
                default -> unitName = entry.getKey();
            }

            parts.add(value + " " + unitName);
        }

        if (parts.isEmpty()) return "0 seconds";

        // Build the final string with commas and "and"
        if (parts.size() == 1) {
            return parts.get(0);
        } else if (parts.size() == 2) {
            return parts.get(0) + " and " + parts.get(1);
        } else {
            // 3 or more parts
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i == parts.size() - 1) {
                    sb.append("and ").append(parts.get(i));
                } else {
                    sb.append(parts.get(i)).append(", ");
                }
            }
            return sb.toString();
        }
    }

    public static String parseMessageWithPlaceholders(String message, Map<String, String> placeholders) {
        if (message == null) {
            return "";
        }
        if (placeholders == null) {
            return message;
        }

        String parsedMessage = message;

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            parsedMessage = parsedMessage.replace(placeholder, value);
        }

        return parsedMessage;
    }

}
