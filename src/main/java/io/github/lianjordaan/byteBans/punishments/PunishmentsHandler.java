package io.github.lianjordaan.byteBans.punishments;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.CommandUtils;
import io.github.lianjordaan.byteBans.util.DatabaseUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class PunishmentsHandler {
    private static ByteBans plugin;
    private Connection connection;
    private Map<Long, PunishmentData> punishments = new HashMap<>();
    private static BBLogger logger;
    private MiniMessage miniMessage = MiniMessage.miniMessage();

    public PunishmentsHandler(ByteBans plugin) throws SQLException {
        this.plugin = plugin;
        this.connection = plugin.getDatabase().getConnection();
        this.logger = plugin.getBBLogger();
    }

    public void reloadPunishments() throws SQLException {
        Map<Long, PunishmentData> oldPunishments = this.punishments;
        Map<Long, PunishmentData> newPunishments = new HashMap<>();
        List<PunishmentData> punishmentsList = DatabaseUtils.getPunishments(connection, plugin.getDatabaseTablePrefix());

        logger.verbose("Loading " + punishmentsList.size() + " punishments into memory...");

        for (PunishmentData punishment : punishmentsList) {
            newPunishments.put(punishment.getId(), punishment);

            PunishmentData oldPunishment = oldPunishments.get(punishment.getId());
            if (oldPunishment == null) {
                // New punishment detected
                logger.verbose("New punishment detected: ID " + punishment.getId());
                announcePunishment(punishment);
            }

            logger.verbose("Loaded punishment with ID " + punishment.getId() + " into memory.");
            logger.verbose("Punishment data: " + punishment.toString());
        }

        // Replace old map with new
        this.punishments = newPunishments;
    }


    public void loadPunishments() throws SQLException {
        punishments.clear();

        List<PunishmentData> punishmentsList = DatabaseUtils.getPunishments(connection, plugin.getDatabaseTablePrefix());
        logger.verbose("Loading " + punishmentsList.size() + " punishments into memory...");
        for (PunishmentData punishment : punishmentsList) {
            punishments.put(punishment.getId(), punishment);
            logger.verbose("Loaded punishment with ID " + punishment.getId() + " into memory.");
            logger.verbose("Punishment data: " + punishment.toString());
        }
    }

    public PunishmentData getPunishment(long id) {
        return punishments.get(id);
    }

    public Map<Long, PunishmentData> getActivePunishments() {
        Map<Long, PunishmentData> activePunishments = new HashMap<>();

        for (Map.Entry<Long, PunishmentData> entry : punishments.entrySet()) {
            PunishmentData punishment = entry.getValue();
            if (punishment.isActive()) {
                activePunishments.put(entry.getKey(), punishment);
            }
        }
        return activePunishments;
    }

    public Map<Long, PunishmentData> getActivePunishments(String uuid) {
        Map<Long, PunishmentData> activePunishments = new HashMap<>();

        for (Map.Entry<Long, PunishmentData> entry : getActivePunishments().entrySet()) {
            PunishmentData punishment = entry.getValue();
            if (punishment.getUuid().equalsIgnoreCase(uuid)) {
                activePunishments.put(entry.getKey(), punishment);
            }
        }
        return activePunishments;
    }

    // Keeps active punishments (not expired)
    public Map<Long, PunishmentData> getNotExpiredPunishments(Map<Long, PunishmentData> punishments) {
        Map<Long, PunishmentData> filtered = new HashMap<>();
        for (Map.Entry<Long, PunishmentData> entry : punishments.entrySet()) {
            PunishmentData punishment = entry.getValue();
            if (punishment.getDuration() == 0 || punishment.getStartTime() + punishment.getDuration() > System.currentTimeMillis()) {
                filtered.put(entry.getKey(), punishment);
            }
        }
        return filtered;
    }

    // Keeps expired punishments
    public Map<Long, PunishmentData> getExpiredPunishments(Map<Long, PunishmentData> punishments) {
        Map<Long, PunishmentData> filtered = new HashMap<>();
        for (Map.Entry<Long, PunishmentData> entry : punishments.entrySet()) {
            PunishmentData punishment = entry.getValue();
            // Permanent punishments (duration == 0) are NOT expired
            if (punishment.getDuration() > 0 && punishment.getStartTime() + punishment.getDuration() <= System.currentTimeMillis()) {
                filtered.put(entry.getKey(), punishment);
            }
        }
        return filtered;
    }

    public boolean isPunishmentPermanent(PunishmentData punishment) {
        if (punishment == null) return false;
        return punishment.getDuration() == 0;
    }

    public long getPunishmentEndTime(PunishmentData punishment) {
        if (punishment == null) return 0;
        return punishment.getStartTime() + punishment.getDuration();
    }

    public PunishmentData isPlayerMuted(String uuid) {
        for (Map.Entry<Long, PunishmentData> entry : getActivePunishments(uuid).entrySet()) {
            Long id = entry.getKey();
            PunishmentData punishment = entry.getValue();
            if (punishment.getType().equalsIgnoreCase("mute")) {
                long endTime = getPunishmentEndTime(punishment);
                boolean isPermanent = isPunishmentPermanent(punishment);
                if (isPermanent || endTime > System.currentTimeMillis()) {
                    return punishment;
                }
            }
        }
        return null;
    }

    public PunishmentData isPlayerBanned(String uuid) {
        for (Map.Entry<Long, PunishmentData> entry : getActivePunishments(uuid).entrySet()) {
            Long id = entry.getKey();
            PunishmentData punishment = entry.getValue();
            if (punishment.getType().equalsIgnoreCase("ban")) {
                long endTime = getPunishmentEndTime(punishment);
                boolean isPermanent = isPunishmentPermanent(punishment);
                if (isPermanent || endTime > System.currentTimeMillis()) {
                    return punishment;
                }
            }
        }
        return null;
    }

    public Result unmutePlayer(String uuid, String punisherUuid, String reason, Long id, boolean silent) {
        Map<Long, PunishmentData> activePunishments = getNotExpiredPunishments(getActivePunishments(uuid));
        if (id == null) {
            for (Map.Entry<Long, PunishmentData> entry : activePunishments.entrySet()) {
                if (entry.getValue().getType().equalsIgnoreCase("mute")) {
                    id = entry.getKey();
                    break;
                }
            }
        }
        logger.verbose("Unmute ID: " + id);
        logger.verbose("<yellow>Pinishment to unmute: " + punishments.get(id).toString() + "</yellow>");
        if (id != null) {
            PunishmentData punishment = punishments.get(id);
            punishment.setActive(false);
            punishment.setUpdatedAt(System.currentTimeMillis());

            punishPlayer(uuid, punisherUuid, "unmute", reason, "*", 0, false, silent);
            boolean success = makePunishmentInactiveInDatabase(id);
            if (success) {
                return new Result(true, "Successfully unmuted player.");
            } else {
                return new Result(false, "Failed to unmute player. Please check console for more details.");
            }
        }
        return new Result(false, "No active mute found for this player.");
    }

    public Result unBanPlayer(String uuid, String punisherUuid, String reason, Long id, boolean silent) {
        Map<Long, PunishmentData> activePunishments = getNotExpiredPunishments(getActivePunishments(uuid));
        if (id == null) {
            for (Map.Entry<Long, PunishmentData> entry : activePunishments.entrySet()) {
                if (entry.getValue().getType().equalsIgnoreCase("ban")) {
                    id = entry.getKey();
                    break;
                }
            }
        }
        if (id != null) {
            PunishmentData punishment = punishments.get(id);
            punishment.setActive(false);
            punishment.setUpdatedAt(System.currentTimeMillis());

            punishPlayer(uuid, punisherUuid, "unban", reason, "*", 0, false, silent);
            boolean success = makePunishmentInactiveInDatabase(id);
            if (success) {
                return new Result(true, "Successfully unbanned player.");
            } else {
                return new Result(false, "Failed to unban player. Please check console for more details.");
            }
        }
        return new Result(false, "No active ban found for this player.");
    }

    public Result unWarnPlayer(String uuid, String punisherUuid, String reason, String scope, Long id) {
        Map<Long, PunishmentData> activePunishments = getNotExpiredPunishments(getActivePunishments(uuid));
        if (id == null) {
            for (Map.Entry<Long, PunishmentData> entry : activePunishments.entrySet()) {
                if (entry.getValue().getType().equalsIgnoreCase("warn")) {
                    id = entry.getKey();
                    break;
                }
            }
        }
        if (id != null) {
            PunishmentData punishment = punishments.get(id);
            punishment.setActive(false);
            punishment.setUpdatedAt(System.currentTimeMillis());

            punishPlayer(uuid, punisherUuid, "unwarn", reason, scope, 0, false, true);
            boolean success = makePunishmentInactiveInDatabase(id);
            if (success) {
                return new Result(true, "Successfully unwarned player.");
            } else {
                return new Result(false, "Failed to unwarn player. Please check console for more details.");
            }
        }
        return new Result(false, "No active warn found for this player.");
    }

    public Result removeNote(String uuid, String punisherUuid, String reason, String scope, Long id) {
        Map<Long, PunishmentData> activePunishments = getNotExpiredPunishments(getActivePunishments(uuid));
        if (id == null) {
            for (Map.Entry<Long, PunishmentData> entry : activePunishments.entrySet()) {
                if (entry.getValue().getType().equalsIgnoreCase("note")) {
                    id = entry.getKey();
                    break;
                }
            }
        }
        if (id != null) {
            PunishmentData punishment = punishments.get(id);
            punishment.setActive(false);
            punishment.setUpdatedAt(System.currentTimeMillis());

            punishPlayer(uuid, punisherUuid, "unnote", reason, scope, 0, false, true);
            boolean success = makePunishmentInactiveInDatabase(id);
            if (success) {
                return new Result(true, "Successfully removed note.");
            } else {
                return new Result(false, "Failed to remove note. Please check console for more details.");
            }
        }
        return new Result(false, "No active note found for this player.");
    }

    public Result removePunishment(String adminUuid, Long id) {
        if (punishments.get(id) == null) {
            return new Result(false, "No punishment found with that ID.");
        }
        punishments.remove(id);
        logger.info("Punishment ID: " + id + " has been removed by " + adminUuid);
        boolean result = removePunishmentInDatabase(id);
        if (result) {
            return new Result(true, "Successfully removed punishment.");
        }
        return new Result(false, "Failed to remove punishment. Please check console for more details.");
    }

    public Result mutePlayer(String uuid, String punisherUuid, String reason, String scope, boolean silent) {
        PunishmentData alreadyMuted = isPlayerMuted(uuid);
        if (alreadyMuted != null) {
            return new Result(false, "Player is already muted.");
        }
        boolean muteResult = punishPlayer(uuid, punisherUuid, "mute", reason, scope, 0, true, silent);
        if (muteResult) {
            return new Result(true, "Successfully muted player.");
        }
        return new Result(false, "Failed to mute player. Please check console for more details.");
    }

    public Result tempMutePlayer(String uuid, String punisherUuid, long duration, String reason, String scope, boolean silent) {
        PunishmentData alreadyMuted = isPlayerMuted(uuid);
        if (alreadyMuted != null) {
            return new Result(false, "Player is already muted.");
        }
        boolean muteResult = punishPlayer(uuid, punisherUuid, "mute", reason, scope, duration, true, silent);
        if (muteResult) {
            return new Result(true, "Successfully tempmuted player.");
        }
        return new Result(false, "Failed to tempmute player. Please check console for more details.");
    }

    public Result banPlayer(String uuid, String punisherUuid, String reason, String scope, boolean silent) {
        PunishmentData alreadyBanned = isPlayerBanned(uuid);
        if (alreadyBanned != null) {
            return new Result(false, "Player is already banned.");
        }
        boolean banResult = punishPlayer(uuid, punisherUuid, "ban", reason, scope, 0, true, silent);
        if (banResult) {
            return new Result(true, "Successfully banned player.");
        }
        return new Result(false, "Failed to ban player. Please check console for more details.");
    }

    public Result tempBanPlayer(String uuid, String punisherUuid, long duration, String reason, String scope, boolean silent) {
        PunishmentData alreadyBanned = isPlayerBanned(uuid);
        if (alreadyBanned != null) {
            return new Result(false, "Player is already banned.");
        }
        boolean banResult = punishPlayer(uuid, punisherUuid, "ban", reason, scope, duration, true, silent);
        if (banResult) {
            return new Result(true, "Successfully tempbanned player.");
        }
        return new Result(false, "Failed to tempban player. Please check console for more details.");
    }

    public boolean kickPlayer(String uuid, String punisherUuid, String reason, String scope, boolean silent) {
        return punishPlayer(uuid, punisherUuid, "kick", reason, scope, 0, true, silent);
    }

    public boolean warnPlayer(String uuid, String punisherUuid, String reason, String scope) {
        return punishPlayer(uuid, punisherUuid, "warn", reason, scope, 0, true, true);
    }

    public boolean warnPlayer(String uuid, String punisherUuid, long duration, String reason, String scope) {
        return punishPlayer(uuid, punisherUuid, "warn", reason, scope, duration, true, true);
    }

    public boolean addNote(String uuid, String punisherUuid, String note, String scope) {
        return punishPlayer(uuid, punisherUuid, "note", note, scope, 0, true, true);
    }

    public boolean punishPlayer(String uuid, String punisherUuid, String type, String reason, String scope, long duration, boolean active, boolean silent) {
        PunishmentData punishment = new PunishmentData();
        punishment.setUuid(uuid);
        punishment.setPunisherUuid(punisherUuid);
        punishment.setType(type);
        punishment.setReason(reason);
        punishment.setScope(scope);
        punishment.setStartTime(System.currentTimeMillis());
        punishment.setDuration(duration);
        punishment.setActive(active);
        punishment.setCreatedAt(System.currentTimeMillis());
        punishment.setUpdatedAt(System.currentTimeMillis());
        punishment.setSilent(silent);

        String sql = "INSERT INTO " + plugin.getDatabaseTablePrefix() + "punishments (uuid, punisher_uuid, type, reason, scope, start_time, duration, active, created_at, updated_at, silent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try {
            long generatedId = DatabaseUtils.executeInsert(connection, sql,
                    punishment.getUuid(),
                    punishment.getPunisherUuid(),
                    punishment.getType(),
                    punishment.getReason(),
                    punishment.getScope(),
                    punishment.getStartTime(),
                    punishment.getDuration(),
                    punishment.isActive(),
                    punishment.getCreatedAt(),
                    punishment.getUpdatedAt(),
                    punishment.isSilent()
            );

            punishment.setId(generatedId);

            sendPunishmentUpdate(generatedId, type, System.currentTimeMillis(), punisherUuid);

            return true;

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return false;
    }

    public void sendPunishmentUpdate(long id, String action, long timestamp, String changedBy) {
        try {
            DatabaseUtils.executeUpdate(connection, "INSERT INTO " + plugin.getDatabaseTablePrefix() + "punishment_updates (punishment_id, action, timestamp, changed_by) VALUES (?, ?, ?, ?)", id, action, timestamp, changedBy);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean makePunishmentInactiveInDatabase(long id) {
        try {
            DatabaseUtils.executeUpdate(connection, "UPDATE " + plugin.getDatabaseTablePrefix() + "punishments SET active = ? WHERE id = ?", false, id);
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Failed to make punishment inactive in database!", e);
            return false;
        }
        return true;
    }

    public boolean removePunishmentInDatabase(long id) {
        try {
            DatabaseUtils.executeUpdate(connection, "DELETE FROM " + plugin.getDatabaseTablePrefix() + "punishments WHERE id = ?", id);
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Failed to remove punishment from database!", e);
            return false;
        }
        return true;
    }

    public Map<Long, PunishmentData> getPunishments() {
        return punishments;
    }

    public List<Long> getPunishmentIds() {
        return getPunishments().keySet().stream().map(Long::valueOf).collect(Collectors.toList());
    }

    public void announcePunishment(PunishmentData punishment) {
        String punisher = punishment.getPunisherUuid();
        String punisherName = "UNKNOWN";
        if (punisher.equalsIgnoreCase("CONSOLE")) {
            punisherName = "CONSOLE";
        } else {
            Result result = CommandUtils.getUsernameFromUuid(punisher);
            if (result.isSuccess()) {
                punisherName = result.getMessage();
            }
        }
        String punishedPlayer = punishment.getUuid();
        String punishedPlayerName = "UNKNOWN";
        if (punishedPlayer.equalsIgnoreCase("CONSOLE")) {
            punishedPlayerName = "CONSOLE";
        } else {
            Result result = CommandUtils.getUsernameFromUuid(punishedPlayer);
            if (result.isSuccess()) {
                punishedPlayerName = result.getMessage();
            }
        }

        Map<String, String> placeholders = new HashMap<>();

        String message = null;
        String broadcast = null;
        String broadcastSilent = null;

        String muteMessage = plugin.getConfig().getString("messages.commands.mute.message");
        String muteBroadcast = plugin.getConfig().getString("messages.commands.mute.broadcast");
        String muteBroadcastSilent = plugin.getConfig().getString("messages.commands.mute.broadcast_silent");

        String tempMuteMessage = plugin.getConfig().getString("messages.commands.tempmute.message");
        String tempMuteBroadcast = plugin.getConfig().getString("messages.commands.tempmute.broadcast");
        String tempMuteBroadcastSilent = plugin.getConfig().getString("messages.commands.tempmute.broadcast_silent");

        String banMessage = plugin.getConfig().getString("messages.commands.ban.message");
        String banBroadcast = plugin.getConfig().getString("messages.commands.ban.broadcast");
        String banBroadcastSilent = plugin.getConfig().getString("messages.commands.ban.broadcast_silent");

        String tempBanMessage = plugin.getConfig().getString("messages.commands.tempban.message");
        String tempBanBroadcast = plugin.getConfig().getString("messages.commands.tempban.broadcast");
        String tempBanBroadcastSilent = plugin.getConfig().getString("messages.commands.tempban.broadcast_silent");

        String kickMessage = plugin.getConfig().getString("messages.commands.kick.message");
        String kickBroadcast = plugin.getConfig().getString("messages.commands.kick.broadcast");
        String kickBroadcastSilent = plugin.getConfig().getString("messages.commands.kick.broadcast_silent");

        String unBanBroadcast = plugin.getConfig().getString("messages.commands.unban.broadcast");
        String unBanBroadcastSilent = plugin.getConfig().getString("messages.commands.unban.broadcast_silent");

        String unMuteMessage = plugin.getConfig().getString("messages.commands.unmute.message");
        String unMuteBroadcast = plugin.getConfig().getString("messages.commands.unmute.broadcast");
        String unMuteBroadcastSilent = plugin.getConfig().getString("messages.commands.unmute.broadcast_silent");

        switch (punishment.getType()) {
            case "ban":
                message = banMessage;
                broadcast = banBroadcast;
                broadcastSilent = banBroadcastSilent;
                if (punishment.getDuration() != 0) {
                    message = tempBanMessage;
                    broadcast = tempBanBroadcast;
                    broadcastSilent = tempBanBroadcastSilent;
                }
                break;
            case "mute":
                message = muteMessage;
                broadcast = muteBroadcast;
                broadcastSilent = muteBroadcastSilent;
                if (punishment.getDuration() != 0) {
                    message = tempMuteMessage;
                    broadcast = tempMuteBroadcast;
                    broadcastSilent = tempMuteBroadcastSilent;
                }
                break;
            case "kick":
                message = kickMessage;
                broadcast = kickBroadcast;
                broadcastSilent = kickBroadcastSilent;
                break;
            case "unban":
                message = "<red>If you are seeing this.. There is either a HUGE problem with the logic of the universe or you have permission bypass...";
                broadcast = unBanBroadcast;
                broadcastSilent = unBanBroadcastSilent;
            case "unmute":
                message = unMuteMessage;
                broadcast = unMuteBroadcast;
                broadcastSilent = unMuteBroadcastSilent;
                break;
            default:
                break;
        }

        if (message == null || broadcast == null || broadcastSilent == null) {
            return;
        }
        placeholders.put("user", punishedPlayerName);
        placeholders.put("executor", punisherName);
        placeholders.put("reason", punishment.getReason());
        placeholders.put("scope", punishment.getScope());
        placeholders.put("punishment_id", String.valueOf(punishment.getId()));
        placeholders.put("duration_left", CommandUtils.formatDurationNatural(punishment.getDuration()));

        if (punishment.isSilent()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("bytebans.notify.silent")) {
                    player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(broadcastSilent, placeholders)));
                }
                if (player.getUniqueId().toString().equalsIgnoreCase(punishedPlayer)) {
                    player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(message, placeholders)));
                }
            }
            logger.info(CommandUtils.parseMessageWithPlaceholders(broadcastSilent, placeholders));
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(broadcast, placeholders)));
            }
            logger.info(CommandUtils.parseMessageWithPlaceholders(broadcast, placeholders));
        }
        if ("ban".equalsIgnoreCase(punishment.getType())) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (Objects.equals(punishment.getUuid(), player.getUniqueId().toString())) {
                    kickBannedPlayer(punishment, player);
                }
            }
        }
        if ("kick".equalsIgnoreCase(punishment.getType())) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (Objects.equals(punishment.getUuid(), player.getUniqueId().toString())) {
                    kickPlayer(punishment, player);
                }
            }
        }
    }

    /**
     * Returns the active punishments filtered by the server name.
     *
     * @param serverName the name of the current server (from config)
     * @return map of punishment ID -> PunishmentData
     */
    public Map<Long, PunishmentData> getActivePunishmentsForServer(String serverName) {
        Map<Long, PunishmentData> filtered = new HashMap<>();

        for (Map.Entry<Long, PunishmentData> entry : getActivePunishments().entrySet()) {
            PunishmentData punishment = entry.getValue();
            String scope = punishment.getScope();

            if (matchesScope(scope, serverName)) {
                filtered.put(entry.getKey(), punishment);
            }
        }

        return filtered;
    }

    /**
     * Returns true if the scope matches the server name.
     * Supports wildcards: *, prefix*, *suffix, *contains*
     */
    public boolean matchesScope(String scope, String serverName) {
        scope = scope.toLowerCase();
        serverName = serverName.toLowerCase();

        if (scope.equals("*")) return true; // matches all
        if (scope.startsWith("*") && scope.endsWith("*")) {
            // *contains*
            String inner = scope.substring(1, scope.length() - 1);
            return serverName.contains(inner);
        } else if (scope.startsWith("*")) {
            // *suffix
            String suffix = scope.substring(1);
            return serverName.endsWith(suffix);
        } else if (scope.endsWith("*")) {
            // prefix*
            String prefix = scope.substring(0, scope.length() - 1);
            return serverName.startsWith(prefix);
        } else {
            // exact match
            return serverName.equals(scope);
        }
    }

    public static boolean hasPunishmentBypass(Player player) {
        boolean operatorBypass = plugin.getConfig().getBoolean("punishments.staff_bypass.allow_operator") && player.isOp();
        boolean permissionBypass = plugin.getConfig().getBoolean("punishments.staff_bypass.allow_permission") && player.hasPermission("bytebans.bypass");
        return operatorBypass || permissionBypass;
    }

    public boolean markExpiredPunishmentsInactive() {
        try {
            // The query sets active = false for all punishments that have a duration > 0
            // and where start_time + duration <= current time (i.e., they are expired)
            String query = "UPDATE " + plugin.getDatabaseTablePrefix() + "punishments " +
                    "SET active = ? " +
                    "WHERE duration > 0 AND (start_time + duration) <= ?";

            long now = System.currentTimeMillis();

            DatabaseUtils.executeUpdate(connection, query, false, now);

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Failed to mark expired punishments inactive!", e);
            return false;
        }
    }

    public String formatMillis(long millis, String format, String timezone) {
        ZoneId zone;

        try {
            zone = ZoneId.of(timezone);
        } catch (Exception ex) {
            logger.error("Invalid timezone '" + timezone + "'. Falling back to server default.");
            zone = ZoneId.systemDefault(); // fallback
        }

        return DateTimeFormatter
                .ofPattern(format)
                .withZone(zone)
                .format(Instant.ofEpochMilli(millis));
    }

    public void kickBannedPlayer(PunishmentData punishment, Player player) {
        String dateFormat = plugin.getConfig().getString("punishments.date_format");
        String dateTimezone = plugin.getConfig().getString("punishments.date_timezone");

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("start_time_formatted", formatMillis(punishment.getStartTime(), dateFormat, dateTimezone));

        String kickMessage;
        boolean permanent = punishment.getDuration() == 0;
        if (permanent) {
            kickMessage = plugin.getConfig().getString("messages.general.banned.permanent");
        } else {
            kickMessage = plugin.getConfig().getString("messages.general.banned.temporary");
        }
        String bypassChatMessage = plugin.getConfig().getString("messages.general.banned.bypass");

        placeholders.put("user", player.getName());
        placeholders.put("executor", punishment.getPunisherUuid());
        placeholders.put("reason", punishment.getReason());
        placeholders.put("scope", punishment.getScope());
        placeholders.put("punishment_id", String.valueOf(punishment.getId()));
        placeholders.put("duration_left", CommandUtils.formatDurationNatural(punishment.getDuration()));

        boolean adminBypass = PunishmentsHandler.hasPunishmentBypass(player);
        boolean notifyAdminBypass = plugin.getConfig().getBoolean("punishments.staff_bypass.notify");
        if (adminBypass) {
            if (notifyAdminBypass) {
                player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(bypassChatMessage, placeholders)));
            }
            logger.verbose(CommandUtils.parseMessageWithPlaceholders(kickMessage, placeholders));
            return;
        }
        player.kick(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(kickMessage, placeholders)), PlayerKickEvent.Cause.BANNED);
    }

    public void kickPlayer(PunishmentData punishment, Player player) {
        String dateFormat = plugin.getConfig().getString("punishments.date_format_time");
        String dateTimezone = plugin.getConfig().getString("punishments.date_timezone");

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("start_time_formatted", formatMillis(punishment.getStartTime(), dateFormat, dateTimezone));

        String kickMessage = plugin.getConfig().getString("messages.general.kick.message");
        String bypassChatMessage = plugin.getConfig().getString("messages.general.kick.bypass");

        placeholders.put("user", player.getName());
        placeholders.put("executor", punishment.getPunisherUuid());
        placeholders.put("reason", punishment.getReason());
        placeholders.put("scope", punishment.getScope());
        placeholders.put("punishment_id", String.valueOf(punishment.getId()));

        boolean adminBypass = PunishmentsHandler.hasPunishmentBypass(player);
        boolean notifyAdminBypass = plugin.getConfig().getBoolean("punishments.staff_bypass.notify");
        if (adminBypass) {
            if (notifyAdminBypass) {
                player.sendMessage(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(bypassChatMessage, placeholders)));
            }
            logger.verbose(CommandUtils.parseMessageWithPlaceholders(kickMessage, placeholders));
            return;
        }
        player.kick(miniMessage.deserialize(CommandUtils.parseMessageWithPlaceholders(kickMessage, placeholders)), PlayerKickEvent.Cause.KICK_COMMAND);
    }
}
