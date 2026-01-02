package io.github.lianjordaan.byteBans.punishments;

import io.github.lianjordaan.byteBans.ByteBans;
import io.github.lianjordaan.byteBans.model.PunishmentData;
import io.github.lianjordaan.byteBans.model.Result;
import io.github.lianjordaan.byteBans.util.BBLogger;
import io.github.lianjordaan.byteBans.util.DatabaseUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PunishmentsHandler {
    private ByteBans  plugin;
    private Connection connection;
    private Map<Long, PunishmentData> punishments = new HashMap<>();
    private BBLogger logger;

    public PunishmentsHandler(ByteBans plugin) throws SQLException {
        this.plugin = plugin;
        this.connection = plugin.getDatabase().getConnection();
        this.logger = plugin.getBBLogger();
    }

    public void reloadPunishments() throws SQLException {
        Map<Long, PunishmentData> newPunishments = new HashMap<>();
        List<PunishmentData> punishmentsList = DatabaseUtils.getPunishments(connection, plugin.getDatabaseTablePrefix());
        logger.verbose("Loading " + punishmentsList.size() + " punishments into memory...");
        for (PunishmentData punishment : punishmentsList) {
            newPunishments.put(punishment.getId(), punishment);
            logger.verbose("Loaded punishment with ID " + punishment.getId() + " into memory.");
            logger.verbose("Punishment data: " + punishment.toString());
        }
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

    public Result unmutePlayer(String uuid, String punisherUuid, String reason, String scope, Long id) {
        Map<Long, PunishmentData> activePunishments = getActivePunishments(uuid);
        if (id == null) {
            for (Map.Entry<Long, PunishmentData> entry : activePunishments.entrySet()) {
                if (entry.getValue().getType().equalsIgnoreCase("mute")) {
                    id = entry.getKey();
                    break;
                }
            }
        }
        if (id != null) {
            PunishmentData punishment = punishments.get(id);
            punishment.setActive(false);
            punishment.setUpdatedAt(System.currentTimeMillis());

            punishPlayer(uuid, punisherUuid, "unmute", reason, scope, 0, false);
            boolean success = makePunishmentInactiveInDatabase(id);
            if (success) {
                return new Result(true, "Successfully unmuted player.");
            } else {
                return new Result(false, "Failed to unmute player. Please check console for more details.");
            }
        }
        return new Result(false, "No active mute found for this player.");
    }

    public Result unBanPlayer(String uuid, String punisherUuid, String reason, String scope, Long id) {
        Map<Long, PunishmentData> activePunishments = getActivePunishments(uuid);
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

            punishPlayer(uuid, punisherUuid, "unban", reason, scope, 0, false);
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
        Map<Long, PunishmentData> activePunishments = getActivePunishments(uuid);
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

            punishPlayer(uuid, punisherUuid, "unwarn", reason, scope, 0, false);
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
        Map<Long, PunishmentData> activePunishments = getActivePunishments(uuid);
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

            punishPlayer(uuid, punisherUuid, "unnote", reason, scope, 0, false);
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

    public Result mutePlayer(String uuid, String punisherUuid, String reason, String scope) {
        PunishmentData alreadyMuted = isPlayerMuted(uuid);
        if (alreadyMuted != null) {
            return new Result(false, "Player is already muted.");
        }
        boolean muteResult = punishPlayer(uuid, punisherUuid, "mute", reason, scope, 0, true);
        if (muteResult) {
            return new Result(true, "Successfully muted player.");
        }
        return new Result(false, "Failed to mute player. Please check console for more details.");
    }

    public Result tempMutePlayer(String uuid, String punisherUuid, long duration, String reason, String scope) {
        PunishmentData alreadyMuted = isPlayerMuted(uuid);
        if (alreadyMuted != null) {
            return new Result(false, "Player is already muted.");
        }
        boolean muteResult = punishPlayer(uuid, punisherUuid, "mute", reason, scope, duration, true);
        if (muteResult) {
            return new Result(true, "Successfully tempmuted player.");
        }
        return new Result(false, "Failed to tempmute player. Please check console for more details.");
    }

    public Result banPlayer(String uuid, String punisherUuid, String reason, String scope) {
        PunishmentData alreadyBanned = isPlayerBanned(uuid);
        if (alreadyBanned != null) {
            return new Result(false, "Player is already banned.");
        }
        boolean banResult = punishPlayer(uuid, punisherUuid, "ban", reason, scope, 0, true);
        if (banResult) {
            return new Result(true, "Successfully banned player.");
        }
        return new Result(false, "Failed to ban player. Please check console for more details.");
    }

    public Result tempBanPlayer(String uuid, String punisherUuid, long duration, String reason, String scope) {
        PunishmentData alreadyBanned = isPlayerBanned(uuid);
        if (alreadyBanned != null) {
            return new Result(false, "Player is already banned.");
        }
        boolean banResult = punishPlayer(uuid, punisherUuid, "ban", reason, scope, duration, true);
        if (banResult) {
            return new Result(true, "Successfully tempbanned player.");
        }
        return new Result(false, "Failed to tempban player. Please check console for more details.");
    }

    public boolean kickPlayer(String uuid, String punisherUuid, String reason, String scope) {
        return punishPlayer(uuid, punisherUuid, "kick", reason, scope, 0, true);
    }

    public boolean warnPlayer(String uuid, String punisherUuid, String reason, String scope) {
        return punishPlayer(uuid, punisherUuid, "warn", reason, scope, 0, true);
    }

    public boolean warnPlayer(String uuid, String punisherUuid, long duration, String reason, String scope) {
        return punishPlayer(uuid, punisherUuid, "warn", reason, scope, duration, true);
    }

    public boolean addNote(String uuid, String punisherUuid, String note, String scope) {
        return punishPlayer(uuid, punisherUuid, "note", note, scope, 0, true);
    }

    public boolean punishPlayer(String uuid, String punisherUuid, String type, String reason, String scope, long duration, boolean active) {
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

        String sql = "INSERT INTO " + plugin.getDatabaseTablePrefix() + "punishments (uuid, punisher_uuid, type, reason, scope, start_time, duration, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

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
                    punishment.getUpdatedAt()
            );

            punishment.setId(generatedId);
            punishments.put(generatedId, punishment);

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
}
