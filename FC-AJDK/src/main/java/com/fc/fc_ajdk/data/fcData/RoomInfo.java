package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.utils.JsonUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Lightweight DTO for sharing all room information between members.
 * Contains name, desc, owner, members, symkey cipher, and symkeyVersion.
 */
public class RoomInfo extends FcObject {
    private String name;
    private String desc;
    private String owner;
    private String symkey;
    private Long symkeyVersion;
    private String[] members;
    private Map<String, String> home;

    public byte[] toBytes() {
        return JsonUtils.toJson(this).getBytes();
    }

    public static RoomInfo fromBytes(byte[] bytes) {
        return JsonUtils.fromJson(new String(bytes), RoomInfo.class);
    }

    public static RoomInfo fromRoom(Room room) {
        if (room == null) return null;
        RoomInfo roomInfo = new RoomInfo();
        roomInfo.setId(room.getId());
        roomInfo.setName(room.getName());
        roomInfo.setDesc(room.getDesc());
        roomInfo.setOwner(room.getOwner());
        roomInfo.setSymkeyVersion(room.getSymkeyVersion());
        roomInfo.setHome(room.getHome());
        if (room.getMembers() != null) {
            roomInfo.setMembers(room.getMembers().toArray(new String[0]));
        }
        return roomInfo;
    }

    /**
     * Apply this RoomInfo to an existing Room, updating its fields.
     * Does not overwrite local-only fields (muted, pinned, active).
     */
    public void applyToRoom(Room room) {
        if (room == null) return;
        if (name != null) room.setName(name);
        if (desc != null) room.setDesc(desc);
        if (owner != null) room.setOwner(owner);
        if (members != null) room.setMembers(new ArrayList<>(Arrays.asList(members)));
        if (symkeyVersion != null) room.setSymkeyVersion(symkeyVersion);
        if (home != null) room.setHome(home);
        room.setLastUpdated(System.currentTimeMillis());
    }

    /**
     * Create a new Room from this RoomInfo.
     */
    public Room toRoom() {
        Room room = new Room();
        room.setId(id);
        room.setName(name);
        room.setDesc(desc);
        room.setOwner(owner);
        room.setSymkeyVersion(symkeyVersion != null ? symkeyVersion : 1L);
        room.setHome(home);
        if (members != null) {
            room.setMembers(new ArrayList<>(Arrays.asList(members)));
        } else {
            room.setMembers(new ArrayList<>());
        }
        room.setActive(true);
        room.setCreated(System.currentTimeMillis());
        room.setLastActive(room.getCreated());
        room.setLastUpdated(room.getCreated());
        return room;
    }

    public String toJson() {
        return JsonUtils.toJson(this);
    }

    public static RoomInfo fromJson(String json) {
        return JsonUtils.fromJson(json, RoomInfo.class);
    }

    // Getters and setters

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String[] getMembers() {
        return members;
    }

    public void setMembers(String[] members) {
        this.members = members;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getSymkey() {
        return symkey;
    }

    public void setSymkey(String symkey) {
        this.symkey = symkey;
    }

    public Long getSymkeyVersion() {
        return symkeyVersion;
    }

    public void setSymkeyVersion(Long symkeyVersion) {
        this.symkeyVersion = symkeyVersion;
    }

    public Map<String, String> getHome() {
        return home;
    }

    public void setHome(Map<String, String> home) {
        this.home = home;
    }
}
