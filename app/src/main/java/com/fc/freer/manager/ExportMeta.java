package com.fc.freer.manager;

import com.fc.fc_ajdk.utils.JsonUtils;

/**
 * Metadata for entity export files (JSONL format).
 * This is the first line of an export file, containing information about the export.
 */
public class ExportMeta {
    private String version;      // Format version, e.g., "1.0"
    private String fid;          // Owner FID
    private Long exportTime;     // Export timestamp in milliseconds
    private Integer count;       // Number of entities in the export
    private String appName;      // Application name, e.g., "Freer"
    private String entityType;   // Entity type, e.g., "hat"
    private String imType;       // IM type for history export: "P2P", "TEAM", "ROOM", "SQUARE"
    private String targetId;     // Conversation target ID
    private Long sinceTs;        // Time range start (ms)
    private Long beforeTs;       // Time range end (ms)
    private String sharedTo;     // Requester FID who requested the history

    public ExportMeta() {
    }

    public ExportMeta(String version, String fid, Long exportTime, Integer count, String appName, String entityType) {
        this.version = version;
        this.fid = fid;
        this.exportTime = exportTime;
        this.count = count;
        this.appName = appName;
        this.entityType = entityType;
    }

    public static ExportMeta create(String fid, int count, String appName, String entityType) {
        return new ExportMeta("1.0", fid, System.currentTimeMillis(), count, appName, entityType);
    }

    public static ExportMeta createHistory(String fid, String imType, String targetId,
                                            Long sinceTs, Long beforeTs, String sharedTo) {
        ExportMeta meta = new ExportMeta("1.0", fid, System.currentTimeMillis(), null, "Freer", "imMessage");
        meta.setImType(imType);
        meta.setTargetId(targetId);
        meta.setSinceTs(sinceTs);
        meta.setBeforeTs(beforeTs);
        meta.setSharedTo(sharedTo);
        return meta;
    }

    public static ExportMeta fromJson(String json) {
        return JsonUtils.fromJson(json, ExportMeta.class);
    }

    public String toJson() {
        return JsonUtils.toJson(this);
    }

    // Getters and Setters

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFid() {
        return fid;
    }

    public void setFid(String fid) {
        this.fid = fid;
    }

    public Long getExportTime() {
        return exportTime;
    }

    public void setExportTime(Long exportTime) {
        this.exportTime = exportTime;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getImType() {
        return imType;
    }

    public void setImType(String imType) {
        this.imType = imType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public Long getSinceTs() {
        return sinceTs;
    }

    public void setSinceTs(Long sinceTs) {
        this.sinceTs = sinceTs;
    }

    public Long getBeforeTs() {
        return beforeTs;
    }

    public void setBeforeTs(Long beforeTs) {
        this.beforeTs = beforeTs;
    }

    public String getSharedTo() {
        return sharedTo;
    }

    public void setSharedTo(String sharedTo) {
        this.sharedTo = sharedTo;
    }
}
