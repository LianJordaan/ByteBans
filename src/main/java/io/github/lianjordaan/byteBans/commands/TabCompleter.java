package io.github.lianjordaan.byteBans.commands;

import io.github.lianjordaan.byteBans.ByteBans;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class TabCompleter implements org.bukkit.command.TabCompleter {
    private final ByteBans plugin;

    private static final List<String> KICK_KEYS = Arrays.asList("user:", "reason:", "scope:");

    private static final List<String> MUTE_KEYS = Arrays.asList("user:", "reason:", "scope:");
    private static final List<String> TEMPMUTE_KEYS = Arrays.asList("user:", "time:", "reason:", "scope:");
    private static final List<String> UNMUTE_KEYS = Arrays.asList("user:", "reason:");

    private static final List<String> BAN_KEYS = Arrays.asList("user:", "reason:", "scope:");
    private static final List<String> TEMPBAN_KEYS = Arrays.asList("user:", "time:", "reason:", "scope:");
    private static final List<String> UNBAN_KEYS = Arrays.asList("user:", "reason:");

    private static final List<String> REMOVEPUNISHMENT_KEYS = Arrays.asList("id:");

    public TabCompleter(ByteBans plugin) {
        this.plugin = plugin;
    }

    private static class ParamState {
        boolean hasUser;
        boolean hasTime;
        boolean hasReason;
        boolean hasScope;
        boolean hasId;
    }

    private ParamState getParamState(String[] args) {
        ParamState state = new ParamState();
        for (String arg : args) {
            String lower = arg.toLowerCase();
            if (lower.startsWith("user:")) state.hasUser = true;
            else if (lower.startsWith("time:")) state.hasTime = true;
            else if (lower.startsWith("reason:")) state.hasReason = true;
            else if (lower.startsWith("scope:")) state.hasScope = true;
            else if (lower.startsWith("id:")) state.hasId = true;
        }
        return state;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> availableServers = plugin.getAvailableServersScanner().getAvailableServers();

        availableServers.add("*");

        List<Long> punishmentIds = plugin.getPunishmentsHandler().getPunishmentIds();
        List<String> punishmentIdsStrings = new ArrayList<>(punishmentIds.stream().map(String::valueOf).distinct().toList());
        punishmentIdsStrings.replaceAll(s -> "id:" + s);
        punishmentIdsStrings.add("id:");

        List<String> keys;
        switch (alias.toLowerCase()) {
            case "mute": keys = new ArrayList<>(MUTE_KEYS); break;
            case "tempmute": keys = new ArrayList<>(TEMPMUTE_KEYS); break;
            case "unmute": keys = new ArrayList<>(UNMUTE_KEYS); break;
            case "ban": keys = new ArrayList<>(BAN_KEYS); break;
            case "tempban": keys = new ArrayList<>(TEMPBAN_KEYS); break;
            case "unban": keys = new ArrayList<>(UNBAN_KEYS); break;
            case "kick": keys = new ArrayList<>(KICK_KEYS); break;
            case "removepunishment": keys = new ArrayList<>(REMOVEPUNISHMENT_KEYS); break;
            default: return Collections.emptyList();
        }

        ParamState state = getParamState(args);
        if (state.hasUser) keys.remove("user:");
        if (state.hasTime) keys.remove("time:");
        if (state.hasReason) keys.remove("reason:");
        if (state.hasScope) keys.remove("scope:");
        if (state.hasId) keys.remove("id:");

        String currentArg = args.length == 0 ? "" : args[args.length - 1];
        List<String> completions = new ArrayList<>();

        // Typing a new key (no colon yet)
        if (!currentArg.contains(":")) {
            List<String> matchingKeys = keys.stream()
                    .filter(k -> k.startsWith(currentArg.toLowerCase()))
                    .collect(Collectors.toList());

            // If only one key matches, immediately suggest its values
            if (matchingKeys.size() == 1) {
                String key = matchingKeys.get(0).replace(":", "");
                switch (key) {
                    case "user":
                        Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .forEach(name -> completions.add("user:" + name));
                        break;
                    case "time":
                        Arrays.asList("1h", "1d", "30m").forEach(t -> completions.add("time:" + t));
                        break;
                    case "scope":
                        Set<String> servers = new LinkedHashSet<>(availableServers);
                        servers.forEach(s -> completions.add("scope:" + s));
                        break;
                    case "reason":
                        completions.add("reason:"); // free text
                        break;
                    case "id":
                        completions.addAll(punishmentIdsStrings);
                        break;
                }
            } else {
                completions.addAll(matchingKeys);
            }
            return StringUtil.copyPartialMatches(currentArg, completions, new ArrayList<>());
        }

        // Typing a key:value
        String[] split = currentArg.split(":", 2);
        String key = split[0].toLowerCase();
        String value = split.length > 1 ? split[1] : "";

        switch (key) {
            case "user":
                Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(value.toLowerCase()))
                        .forEach(name -> completions.add("user:" + name));
                break;
            case "time":
                Arrays.asList("1h", "1d", "30m").forEach(t -> completions.add("time:" + t));
                break;
            case "scope":
                // Already typed servers
                Set<String> typedServers = new LinkedHashSet<>(Arrays.asList(value.split(",")));

                // Suggest remaining servers
                for (String s : availableServers) {
                    if (!typedServers.contains(s)) {
                        String suggestion = String.join(",", typedServers) + (typedServers.isEmpty() ? "" : ",") + s;
                        if (suggestion.startsWith(",")) {
                            suggestion = suggestion.substring(1);
                        }
                        completions.add("scope:" + suggestion);
                    }
                }
                break;
            case "reason":
                // free text; no suggestions
                break;
            case "id":
                completions.addAll(punishmentIdsStrings);
                break;
        }

        // Also suggest remaining keys if value is empty
        if (value.isEmpty()) completions.addAll(keys);

        return StringUtil.copyPartialMatches(currentArg, completions, new ArrayList<>());
    }

}
