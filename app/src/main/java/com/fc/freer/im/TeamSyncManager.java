package com.fc.freer.im;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.Values;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.im.dock.DockServiceRegistry;
import com.fc.freer.im.handler.TeamHandler;
import com.tencent.mmkv.MMKV;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Syncs on-chain teams where liveFid is a member.
 *
 * Every run walks all of them, not only those changed since the last run: a
 * team whose record never changes again would otherwise never come back once
 * its conversation is lost locally — deleted from the talk list, or marked
 * left by a leave the chain rejected (an owner cannot leave their own team).
 * A FID belongs to few teams, so the full walk is cheap.
 */
public class TeamSyncManager {
    private static final String TAG = "TeamSyncManager";
    private static final String MMKV_PREFIX = "team_sync_";
    private static final String LOCAL_LEAVE_PREFIX = "local_leave_";
    private static final int PAGE_SIZE = 20;
    /** A local leave not yet on chain keeps its team hidden for this long. */
    private static final long LOCAL_LEAVE_GRACE_MS = 2 * 60 * 60 * 1000L;

    private final String liveFid;
    private final MMKV cursorStore;
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public interface SyncCallback {
        void onSyncComplete(int newCount, int updatedCount);
        void onSyncError(String error);
    }

    public TeamSyncManager(String liveFid) {
        this.liveFid = liveFid;
        this.cursorStore = MMKV.mmkvWithID(MMKV_PREFIX + liveFid, MMKV.SINGLE_PROCESS_MODE);
    }

    /**
     * Record that liveFid asked to leave or disband these teams, so a sync run
     * before the TX confirms does not show them again.
     */
    public static void markLeftLocally(String liveFid, List<String> teamIds) {
        if (liveFid == null || teamIds == null) return;
        try {
            MMKV store = MMKV.mmkvWithID(MMKV_PREFIX + liveFid, MMKV.SINGLE_PROCESS_MODE);
            long now = System.currentTimeMillis();
            for (String tid : teamIds) {
                if (tid != null) store.encode(LOCAL_LEAVE_PREFIX + tid, now);
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to record local leave: %s", e.getMessage());
        }
    }

    private boolean recentlyLeftLocally(String teamId) {
        long at = cursorStore.decodeLong(LOCAL_LEAVE_PREFIX + teamId, 0L);
        return at > 0 && System.currentTimeMillis() - at < LOCAL_LEAVE_GRACE_MS;
    }

    public void sync(FapiClient fapiClient, TeamHandler teamHandler,
                     LocalDB<Conversation> conversationsDb,
                     DockServiceRegistry dockRegistry, SyncCallback callback) {
        if (!syncing.compareAndSet(false, true)) {
            TimberLogger.d(TAG, "Sync already running, skipping");
            return;
        }

        try {
            int newCount = 0;
            int updatedCount = 0;
            List<String> cursor = null;

            while (true) {
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addNewFilter().addNewTerms()
                        .addNewFields(FieldNames.MEMBERS)
                        .addNewValues(liveFid);
                fcdsl.addSort(FieldNames.LAST_HEIGHT, Values.ASC);
                fcdsl.addSort("id", Values.ASC);
                fcdsl.addSize(PAGE_SIZE);

                if (cursor != null && !cursor.isEmpty()) {
                    fcdsl.setAfter(cursor);
                }

                List<Team> teams = fapiClient.entitySearch("team", fcdsl, Team.class);
                if (teams == null || teams.isEmpty()) break;

                for (Team team : teams) {
                    if (team == null || team.getId() == null) continue;

                    Team existing = teamHandler.getTeam(team.getId());
                    boolean homeChanged = hasHomeChanged(existing, team);

                    teamHandler.saveTeamPublic(team);

                    String convId = ImType.TEAM.name() + "_" + team.getId();
                    Conversation conv = conversationsDb.get(convId);

                    boolean liveFidIsMember = team.getMembers() != null
                            && team.getMembers().contains(liveFid);
                    boolean isActive = team.isActive() == null || team.isActive();

                    if (conv == null && liveFidIsMember && isActive) {
                        conv = new Conversation();
                        conv.setId(convId);
                        conv.setType(ImType.TEAM);
                        conv.setTargetId(team.getId());
                        conv.setLeftGroup(false);
                        conv.setCreatedAt(team.getBirthTime());
                        updateConvFromTeam(conv, team);
                        conversationsDb.put(convId, conv);
                        registerTeamDock(team, dockRegistry, fapiClient);
                        newCount++;
                    } else if (conv != null) {
                        updateConvFromTeam(conv, team);

                        if (liveFidIsMember && isActive) {
                            if (conv.getLeftGroup() != null && conv.getLeftGroup()) {
                                if (recentlyLeftLocally(team.getId())) {
                                    // Our leave is on its way to the chain: keep it hidden
                                    continue;
                                }
                                conv.setLeftGroup(false);
                                registerTeamDock(team, dockRegistry, fapiClient);
                                TimberLogger.i(TAG, "Rejoined team: %s", team.getId());
                            } else if (homeChanged) {
                                registerTeamDock(team, dockRegistry, fapiClient);
                                TimberLogger.i(TAG, "Home changed, re-registered dock for team: %s", team.getId());
                            }
                        } else {
                            if (conv.getLeftGroup() == null || !conv.getLeftGroup()) {
                                conv.setLeftGroup(true);
                                if (dockRegistry != null) {
                                    dockRegistry.unregister("team", team.getId());
                                }
                                TimberLogger.i(TAG, "Left or disbanded team: %s", team.getId());
                            }
                        }
                        conversationsDb.put(convId, conv);
                        updatedCount++;
                    }
                }

                if (teams.size() < PAGE_SIZE) break;
                cursor = afterLastTeam(teams);
                if (cursor == null) break;
            }

            if (callback != null) callback.onSyncComplete(newCount, updatedCount);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Sync failed: %s", e.getMessage());
            if (callback != null) callback.onSyncError(e.getMessage());
        } finally {
            syncing.set(false);
        }
    }

    private void updateConvFromTeam(Conversation conv, Team team) {
        conv.setDisplayName(team.getStdName());
        if (team.getOwner() != null) {
            conv.setAvatarDid(team.getOwner());
        }
        conv.setMemberNum(team.getMemberNum());
        conv.settCdd(team.gettCdd());
    }

    private boolean hasHomeChanged(Team existing, Team updated) {
        if (existing == null) return false;
        Map<String, String> oldHome = existing.getHome();
        Map<String, String> newHome = updated.getHome();
        return !Objects.equals(oldHome, newHome);
    }

    private void registerTeamDock(Team team, DockServiceRegistry dockRegistry, FapiClient fapiClient) {
        if (dockRegistry != null && fapiClient != null) {
            dockRegistry.registerTeam(team, fapiClient);
        }
    }

    private static List<String> afterLastTeam(List<Team> teams) {
        Team last = teams.get(teams.size() - 1);
        if (last.getLastHeight() == null || last.getId() == null) return null;
        return List.of(String.valueOf(last.getLastHeight()), last.getId());
    }
}
