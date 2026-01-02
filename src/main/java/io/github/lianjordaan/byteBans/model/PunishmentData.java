package io.github.lianjordaan.byteBans.model;

public class PunishmentData {
    public long id;
    public String uuid;
    public String punisherUuid;
    public String type;
    public String reason;
    public String scope;
    public long startTime;
    public long duration;
    public boolean active;
    public long createdAt;
    public long updatedAt;

    public long getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public String getPunisherUuid() {
        return punisherUuid;
    }

    public String getType() {
        return type;
    }

    public String getReason() {
        return reason;
    }

    public String getScope() {
        return scope;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isActive() {
        return active;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setId(long id) {
        this.id = id;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public void setPunisherUuid(String punisherUuid) {
        this.punisherUuid = punisherUuid;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "PunishmentData{" +
                "id=" + id +
                ", uuid='" + uuid + '\'' +
                ", punisherUuid='" + punisherUuid + '\'' +
                ", type='" + type + '\'' +
                ", reason='" + reason + '\'' +
                ", scope='" + scope + '\'' +
                ", startTime=" + startTime +
                ", duration=" + duration +
                ", active=" + active +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }

    public PunishmentData copy() {
        PunishmentData copy = new PunishmentData();
        copy.setId(this.id);
        copy.setUuid(this.uuid);
        copy.setPunisherUuid(this.punisherUuid);
        copy.setType(this.type);
        copy.setReason(this.reason);
        copy.setScope(this.scope);
        copy.setStartTime(this.startTime);
        copy.setDuration(this.duration);
        copy.setActive(this.active);
        copy.setCreatedAt(this.createdAt);
        copy.setUpdatedAt(this.updatedAt);
        return copy;
    }
}
