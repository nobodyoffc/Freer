package com.fc.freer.im;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.TalkPartner;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.DatabaseManager;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages pending issues that require user action,
 * such as stranger peer connection requests.
 */
public class PendingIssueManager {
    private static final String TAG = "PendingIssueManager";

    private final Context context;
    private final LocalDB<PendingIssue> db;
    private final String liveFid;
    private ContactPolicy contactPolicy;

    private ImManager imManager;
    private FudpNode fudpNode;
    private CountChangeListener countChangeListener;

    private final Set<String> knownPeerFids = ConcurrentHashMap.newKeySet();

    /**
     * Teams with a consensus-change issue still open. Team sync asks about every team it stores,
     * so the common answer — "this team owes nothing" — has to be free rather than a scan of the
     * issue store per team.
     */
    private final Set<String> openConsensusTeamIds = ConcurrentHashMap.newKeySet();

    public interface CountChangeListener {
        void onPendingCountChanged(int newCount);
    }

    public PendingIssueManager(Context context, String liveFid, ContactPolicy contactPolicy) {
        this.context = context.getApplicationContext();
        this.liveFid = liveFid;
        this.contactPolicy = contactPolicy;
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.db = dbManager.getEntityDatabase(liveFid, PendingIssue.class, PendingIssue.class);
        loadKnownPeers();
    }

    public void setImManager(ImManager imManager) {
        this.imManager = imManager;
    }

    public void setFudpNode(FudpNode fudpNode) {
        this.fudpNode = fudpNode;
    }

    public void setCountChangeListener(CountChangeListener listener) {
        this.countChangeListener = listener;
    }

    public void setContactPolicy(ContactPolicy contactPolicy) {
        this.contactPolicy = contactPolicy;
    }

    private void loadKnownPeers() {
        try {
            Map<String, PendingIssue> all = db.getAll();
            for (PendingIssue issue : all.values()) {
                if (issue == null) continue;
                if (issue.getPeerFid() != null) {
                    knownPeerFids.add(issue.getPeerFid());
                }
                if (issue.getIssueType() == PendingIssue.IssueType.CONSENSUS_CHANGE
                        && (issue.getStatus() == PendingIssue.IssueStatus.PENDING
                            || issue.getStatus() == PendingIssue.IssueStatus.DEFERRED)) {
                    PendingIssue.ConsensusChangeData data =
                            issue.getDataAs(PendingIssue.ConsensusChangeData.class);
                    if (data != null && data.teamId != null) {
                        openConsensusTeamIds.add(data.teamId);
                    }
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load known peers: %s", e.getMessage());
        }
    }

    /**
     * Add a stranger peer issue. Returns true if a new issue was created
     * (false if the peer already has a pending/resolved issue).
     */
    public boolean addStrangerPeerIssue(String peerFid) {
        if (peerFid == null) return false;

        if (contactPolicy != null && (contactPolicy.isWhitelisted(peerFid) || contactPolicy.isContact(peerFid))) {
            return false;
        }

        if (knownPeerFids.contains(peerFid)) {
            PendingIssue existing = db.get("STRANGER_PEER_" + peerFid);
            if (existing != null && existing.getStatus() == PendingIssue.IssueStatus.PENDING) {
                return false;
            }
            if (existing != null && (existing.getStatus() == PendingIssue.IssueStatus.ACCEPTED
                    || existing.getStatus() == PendingIssue.IssueStatus.REJECTED)) {
                return false;
            }
        }

        PendingIssue issue = PendingIssue.createStrangerPeer(peerFid);
        db.put(issue.getId(), issue);
        knownPeerFids.add(peerFid);
        notifyCountChange();
        toastNewIssue(context.getString(R.string.stranger_peer_request));
        TimberLogger.i(TAG, "Stranger peer issue created for: %s", peerFid);
        return true;
    }

    /**
     * Add a room invite issue. Returns true if a new issue was created.
     * Uses the roomId as a dedup key so duplicate invites for the same room are ignored.
     */
    public boolean addRoomInviteIssue(String senderFid, String roomId, String roomName,
                                       String roomInfoJson) {
        if (senderFid == null || roomId == null) return false;

        String issueId = "ROOM_INVITE_" + roomId;
        PendingIssue existing = db.get(issueId);
        if (existing != null && existing.getStatus() == PendingIssue.IssueStatus.PENDING) {
            return false;
        }

        PendingIssue issue = PendingIssue.createRoomInvite(senderFid, roomId, roomName, roomInfoJson);
        db.put(issue.getId(), issue);
        notifyCountChange();
        toastNewIssue(context.getString(R.string.room_invite_request));
        TimberLogger.i(TAG, "Room invite issue created: room=%s from=%s", roomId, senderFid);
        return true;
    }

    /**
     * Accept a room invite issue.
     * Delegates to ImManager to create the room and store the symkey.
     */
    public void acceptRoomInvite(String issueId) {
        PendingIssue issue = db.get(issueId);
        if (issue == null) return;

        issue.setStatus(PendingIssue.IssueStatus.ACCEPTED);
        issue.setResolvedAt(System.currentTimeMillis());
        db.put(issue.getId(), issue);

        PendingIssue.RoomInviteData data = issue.getDataAs(PendingIssue.RoomInviteData.class);
        if (data != null && data.roomInfoJson != null && imManager != null) {
            imManager.handleAcceptedRoomInvite(data.roomInfoJson);
        }

        notifyCountChange();
        TimberLogger.i(TAG, "Room invite accepted: %s", issueId);
    }

    /**
     * Reject a room invite issue.
     * Sends a ROOM_LEAVE to the room owner so the owner removes this FID from the member list.
     */
    public void rejectRoomInvite(String issueId) {
        PendingIssue issue = db.get(issueId);
        if (issue == null) return;

        issue.setStatus(PendingIssue.IssueStatus.REJECTED);
        issue.setResolvedAt(System.currentTimeMillis());
        db.put(issue.getId(), issue);

        PendingIssue.RoomInviteData data = issue.getDataAs(PendingIssue.RoomInviteData.class);
        if (data != null && data.roomInfoJson != null && imManager != null) {
            imManager.handleRejectedRoomInvite(data.roomInfoJson);
        }

        notifyCountChange();
        TimberLogger.i(TAG, "Room invite rejected: %s", issueId);
    }

    // ========== Team invitation ==========

    /**
     * Raise a "you have been invited to a team" issue.
     * <p>
     * Deduped per team, and re-raised only when the previous issue is no longer open — so a
     * resent invitation reaches a user who ignored the first one, without stacking duplicates
     * while one is still waiting for an answer.
     *
     * @return the issue when one was created or re-opened, else null
     */
    public PendingIssue addTeamInviteIssue(String senderFid, String teamId, String teamName,
                                            boolean transfer) {
        if (senderFid == null || teamId == null) return null;

        PendingIssue existing = db.get(PendingIssue.teamInviteIssueId(teamId));
        if (existing != null && (existing.getStatus() == PendingIssue.IssueStatus.PENDING
                || existing.getStatus() == PendingIssue.IssueStatus.ACCEPTED)) {
            return null;
        }

        PendingIssue issue = PendingIssue.createTeamInvite(senderFid, teamId, teamName, transfer);
        db.put(issue.getId(), issue);
        notifyCountChange();
        toastNewIssue(context.getString(transfer
                ? R.string.team_transfer_notification_title
                : R.string.team_invite_notification_title));
        TimberLogger.i(TAG, "Team invite issue created: team=%s from=%s", teamId, senderFid);
        return issue;
    }

    /**
     * Mark a team invitation resolved. Joining is an on-chain act carried out by
     * {@link JoinTeamActivity}, so this only records the user's decision.
     */
    public void resolveTeamInvite(String issueId, PendingIssue.IssueStatus status) {
        PendingIssue issue = db.get(issueId);
        if (issue == null || issue.getStatus() == status) return;

        issue.setStatus(status);
        issue.setResolvedAt(System.currentTimeMillis());
        db.put(issue.getId(), issue);
        notifyCountChange();
        TimberLogger.i(TAG, "Team invite %s resolved: %s", issueId, status);
    }

    // ========== History request ==========

    /**
     * Keep a history request for the user to decide on. Stored rather than only prompted:
     * handing over a transcript needs a person, and the request has to survive a restart and
     * reach the user even when the chat it concerns was not open when it arrived.
     *
     * @return the issue when one was created, else null (the same request was already recorded)
     */
    public PendingIssue addHistoryRequestIssue(String requesterFid, String nonce, ImType imType,
                                                String targetId, String requestedTargetId,
                                                long since, long before, String threadName) {
        if (requesterFid == null || nonce == null || imType == null || targetId == null) return null;

        String issueId = PendingIssue.historyRequestIssueId(requesterFid, nonce);
        if (db.get(issueId) != null) return null;

        PendingIssue issue = PendingIssue.createHistoryRequest(requesterFid, nonce, imType,
                targetId, requestedTargetId, since, before, threadName);
        db.put(issue.getId(), issue);
        notifyCountChange();
        toastNewIssue(context.getString(R.string.history_request_issue));
        TimberLogger.i(TAG, "History request issue created: %s %s from=%s", imType, targetId, requesterFid);
        return issue;
    }

    /** Record the user's decision on a history request. Declining sends nothing. */
    public void resolveHistoryRequest(String issueId, PendingIssue.IssueStatus status) {
        PendingIssue issue = db.get(issueId);
        if (issue == null || issue.getStatus() == status) return;

        issue.setStatus(status);
        issue.setResolvedAt(System.currentTimeMillis());
        db.put(issue.getId(), issue);
        notifyCountChange();
        TimberLogger.i(TAG, "History request %s resolved: %s", issueId, status);
    }

    /**
     * Raise — or refresh — the issue for history that could not be downloaded after the
     * automatic attempts. A refresh after a failed Retry only updates the error; it neither
     * toasts again nor re-opens an issue the user has already dismissed.
     *
     * @return the issue when one was newly raised, else null
     */
    public PendingIssue addOrUpdateHistoryImportFailedIssue(PendingIssue.HistoryImportFailedData data,
                                                            String threadName) {
        if (data == null || data.nonce == null || data.senderFid == null) return null;

        String issueId = PendingIssue.historyImportFailedIssueId(data.nonce);
        PendingIssue existing = db.get(issueId);
        if (existing != null) {
            if (existing.getStatus() != PendingIssue.IssueStatus.PENDING
                    && existing.getStatus() != PendingIssue.IssueStatus.DEFERRED) return null;
            existing.setDataFrom(data);
            db.put(existing.getId(), existing);
            return null;
        }

        PendingIssue issue = PendingIssue.createHistoryImportFailed(data, threadName);
        db.put(issue.getId(), issue);
        notifyCountChange();
        toastNewIssue(context.getString(R.string.history_import_failed_issue));
        TimberLogger.i(TAG, "History import failed issue created: nonce=%s from=%s",
                data.nonce, data.senderFid);
        return issue;
    }

    /** Close the failed-import issue for an answer, if one was raised. */
    public void resolveHistoryImportFailed(String nonce, PendingIssue.IssueStatus status) {
        if (nonce == null) return;
        resolveHistoryRequest(PendingIssue.historyImportFailedIssueId(nonce), status);
    }

    /**
     * Open history issues about one thread on this device — requests waiting for a decision and
     * shared history that could not be added — oldest first.
     */
    public List<PendingIssue> getOpenHistoryIssues(ImType imType, String targetId) {
        List<PendingIssue> result = new ArrayList<>();
        if (imType == null || targetId == null) return result;
        for (PendingIssue issue : getOpenIssues()) {
            ImType issueImType;
            String issueTargetId;
            if (issue.getIssueType() == PendingIssue.IssueType.HISTORY_REQUEST) {
                PendingIssue.HistoryRequestData data =
                        issue.getDataAs(PendingIssue.HistoryRequestData.class);
                if (data == null) continue;
                issueImType = data.imType;
                issueTargetId = data.targetId;
            } else if (issue.getIssueType() == PendingIssue.IssueType.HISTORY_IMPORT_FAILED) {
                PendingIssue.HistoryImportFailedData data =
                        issue.getDataAs(PendingIssue.HistoryImportFailedData.class);
                if (data == null) continue;
                issueImType = data.imType;
                issueTargetId = data.targetId;
            } else {
                continue;
            }
            if (issueImType == imType && targetId.equals(issueTargetId)) result.add(issue);
        }
        Collections.reverse(result);
        return result;
    }

    // ========== Consensus change ==========

    /**
     * Raise a "sign the new consensus" issue for a team whose owner changed the consensus while
     * this FID was a member. Deduped per consensus version, so a re-sync does not re-raise one the
     * user has already deferred or acted on, while a <i>further</i> change raises a fresh issue.
     *
     * @return true when a new issue was created
     */
    public boolean addConsensusChangeIssue(String teamId, String teamName, String ownerFid,
                                            String oldConsensusId, String newConsensusId,
                                            String diskSid) {
        if (teamId == null || newConsensusId == null) return false;

        String issueId = PendingIssue.consensusIssueId(teamId, newConsensusId);
        if (db.get(issueId) != null) return false;

        PendingIssue issue = PendingIssue.createConsensusChange(teamId, teamName, ownerFid,
                oldConsensusId, newConsensusId, diskSid);
        db.put(issue.getId(), issue);
        openConsensusTeamIds.add(teamId);
        notifyCountChange();
        toastNewIssue(context.getString(R.string.consensus_change_request));
        TimberLogger.i(TAG, "Consensus change issue created: team=%s consensus=%s", teamId, newConsensusId);
        return true;
    }

    /**
     * Mark a consensus-change issue resolved with the given status. Called when the signature is
     * broadcast, when the member leaves, and when a sync shows the obligation is already gone
     * (signed on another device, or dismissed from the team).
     */
    public void resolveConsensusIssue(String issueId, PendingIssue.IssueStatus status) {
        PendingIssue issue = db.get(issueId);
        if (issue == null || issue.getStatus() == status) return;

        issue.setStatus(status);
        issue.setResolvedAt(System.currentTimeMillis());
        db.put(issue.getId(), issue);

        PendingIssue.ConsensusChangeData data =
                issue.getDataAs(PendingIssue.ConsensusChangeData.class);
        if (data != null && data.teamId != null) openConsensusTeamIds.remove(data.teamId);

        notifyCountChange();
        TimberLogger.i(TAG, "Consensus issue %s resolved: %s", issueId, status);
    }

    /**
     * Resolve any open consensus-change issue for a team — the member no longer owes a signature,
     * whichever way that came about. The consensus version is part of the issue id, so this also
     * clears issues raised for consensus versions that have since been superseded.
     */
    public void clearConsensusIssuesForTeam(String teamId, PendingIssue.IssueStatus status) {
        if (teamId == null || !openConsensusTeamIds.remove(teamId)) return;
        String prefix = "CONSENSUS_" + teamId + "_";
        try {
            Map<String, PendingIssue> all = db.getAll();
            for (PendingIssue issue : all.values()) {
                if (issue == null || issue.getId() == null) continue;
                if (!issue.getId().startsWith(prefix)) continue;
                if (issue.getStatus() != PendingIssue.IssueStatus.PENDING
                        && issue.getStatus() != PendingIssue.IssueStatus.DEFERRED) continue;
                resolveConsensusIssue(issue.getId(), status);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to clear consensus issues: %s", e.getMessage());
        }
    }

    /**
     * Set an issue aside without acting on it. It keeps showing in the issue list but stops
     * counting towards the Todo badge, so the user is not nagged about a decision they postponed.
     */
    public void defer(String issueId) {
        PendingIssue issue = db.get(issueId);
        if (issue == null || issue.getStatus() != PendingIssue.IssueStatus.PENDING) return;

        issue.setStatus(PendingIssue.IssueStatus.DEFERRED);
        db.put(issue.getId(), issue);
        notifyCountChange();
        TimberLogger.i(TAG, "Issue deferred: %s", issueId);
    }

    /**
     * Update message count and preview data on an existing stranger peer issue.
     * Called by ImManager each time a quarantined message is stored.
     */
    public void updateStrangerMessageCount(String peerFid, String messageContent) {
        if (peerFid == null) return;

        String issueId = "STRANGER_PEER_" + peerFid;
        PendingIssue issue = db.get(issueId);
        if (issue == null) {
            // Issue might not exist yet if peer connected without a prior onPeerConnected
            addStrangerPeerIssue(peerFid);
            issue = db.get(issueId);
            if (issue == null) return;
        }

        int count = issue.getMessageCount() + 1;
        issue.setMessageCount(count);

        PendingIssue.StrangerPeerData data = issue.getDataAs(PendingIssue.StrangerPeerData.class);
        if (data == null) data = new PendingIssue.StrangerPeerData();

        long now = System.currentTimeMillis();
        if (data.firstMessageContent == null) {
            data.firstMessageContent = truncate(messageContent, 200);
            data.firstMessageAt = now;
        }
        data.lastMessageContent = truncate(messageContent, 200);
        data.lastMessageAt = now;
        issue.setDataFrom(data);

        db.put(issue.getId(), issue);
        notifyCountChange();
    }

    /**
     * Accept a pending issue: whitelist the peer, add as talk partner,
     * and promote all quarantined messages into normal conversations.
     */
    public void accept(String issueId) {
        PendingIssue issue = db.get(issueId);
        if (issue == null) return;

        issue.setStatus(PendingIssue.IssueStatus.ACCEPTED);
        issue.setResolvedAt(System.currentTimeMillis());
        db.put(issue.getId(), issue);

        String fid = issue.getPeerFid();
        if (fid != null) {
            if (contactPolicy != null) contactPolicy.addToWhitelist(fid);
            if (imManager != null) {
                TalkPartner partner = new TalkPartner();
                partner.setFid(fid);
                partner.setAddedAt(System.currentTimeMillis());
                // Do NOT use peerAlias as the CID: it's only the last 4 chars of
                // the FID, a placeholder label. Leaving CID null lets the real CID
                // be resolved later (or the full FID shown as fallback).
                imManager.addTalkPartner(partner);
                imManager.promoteQuarantinedMessages(fid);
            }
        }

        notifyCountChange();
        TimberLogger.i(TAG, "Stranger peer accepted: %s", fid);
    }

    /**
     * Reject a pending issue: blacklist the peer, disconnect,
     * and purge all quarantined messages.
     */
    public void reject(String issueId) {
        PendingIssue issue = db.get(issueId);
        if (issue == null) return;

        issue.setStatus(PendingIssue.IssueStatus.REJECTED);
        issue.setResolvedAt(System.currentTimeMillis());
        db.put(issue.getId(), issue);

        String fid = issue.getPeerFid();
        if (fid != null) {
            if (contactPolicy != null) contactPolicy.addToBlacklist(fid);
            if (imManager != null) {
                imManager.purgeQuarantinedMessages(fid);
            }
            if (fudpNode != null) {
                try {
                    fudpNode.removePeer(fid);
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to remove peer %s: %s", fid, e.getMessage());
                }
            }
        }

        notifyCountChange();
        TimberLogger.i(TAG, "Stranger peer rejected: %s", fid);
    }

    /**
     * Remove issues older than {@link ImManager#QUARANTINE_EXPIRY_MS}
     * and purge their quarantined messages.
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        try {
            Map<String, PendingIssue> all = db.getAll();
            for (PendingIssue issue : all.values()) {
                if (issue == null) continue;
                if (issue.getStatus() != PendingIssue.IssueStatus.PENDING) continue;
                // History issues wait for the user however long it takes, and the peer is not
                // a quarantined stranger whose messages should be purged.
                if (issue.getIssueType() == PendingIssue.IssueType.HISTORY_REQUEST
                        || issue.getIssueType() == PendingIssue.IssueType.HISTORY_IMPORT_FAILED) continue;
                Long created = issue.getCreatedAt();
                if (created != null && now - created > ImManager.QUARANTINE_EXPIRY_MS) {
                    issue.setStatus(PendingIssue.IssueStatus.EXPIRED);
                    issue.setResolvedAt(now);
                    db.put(issue.getId(), issue);

                    String fid = issue.getPeerFid();
                    if (fid != null && imManager != null) {
                        imManager.purgeQuarantinedMessages(fid);
                    }
                    TimberLogger.i(TAG, "Expired stranger peer issue: %s", issue.getPeerFid());
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to cleanup expired issues: %s", e.getMessage());
        }
        notifyCountChange();
    }

    /**
     * Get all pending issues (status == PENDING), sorted by creation time descending.
     */
    public List<PendingIssue> getPendingIssues() {
        List<PendingIssue> pending = new ArrayList<>();
        try {
            Map<String, PendingIssue> all = db.getAll();
            for (PendingIssue issue : all.values()) {
                if (issue != null && issue.getStatus() == PendingIssue.IssueStatus.PENDING) {
                    pending.add(issue);
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to get pending issues: %s", e.getMessage());
        }
        Collections.sort(pending, (a, b) -> {
            long at = a.getCreatedAt() != null ? a.getCreatedAt() : 0;
            long bt = b.getCreatedAt() != null ? b.getCreatedAt() : 0;
            return Long.compare(bt, at);
        });
        return pending;
    }

    /**
     * Get a single pending issue by ID.
     */
    public PendingIssue getIssue(String issueId) {
        return db.get(issueId);
    }

    /**
     * Issues still awaiting a decision: PENDING plus DEFERRED. Deferred issues stay visible in the
     * issue list so a postponed decision can be picked back up, but they do not count towards the
     * Todo badge — see {@link #getPendingCount()}.
     */
    public List<PendingIssue> getOpenIssues() {
        List<PendingIssue> open = new ArrayList<>();
        try {
            Map<String, PendingIssue> all = db.getAll();
            for (PendingIssue issue : all.values()) {
                if (issue == null) continue;
                if (issue.getStatus() == PendingIssue.IssueStatus.PENDING
                        || issue.getStatus() == PendingIssue.IssueStatus.DEFERRED) {
                    open.add(issue);
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to get open issues: %s", e.getMessage());
        }
        Collections.sort(open, (a, b) -> {
            long at = a.getCreatedAt() != null ? a.getCreatedAt() : 0;
            long bt = b.getCreatedAt() != null ? b.getCreatedAt() : 0;
            return Long.compare(bt, at);
        });
        return open;
    }

    /**
     * Get all issues (including resolved), sorted by creation time descending.
     */
    public List<PendingIssue> getAllIssues() {
        List<PendingIssue> issues = new ArrayList<>();
        try {
            Map<String, PendingIssue> all = db.getAll();
            for (PendingIssue issue : all.values()) {
                if (issue != null) {
                    issues.add(issue);
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to get all issues: %s", e.getMessage());
        }
        Collections.sort(issues, (a, b) -> {
            long at = a.getCreatedAt() != null ? a.getCreatedAt() : 0;
            long bt = b.getCreatedAt() != null ? b.getCreatedAt() : 0;
            return Long.compare(bt, at);
        });
        return issues;
    }

    public int getPendingCount() {
        int count = 0;
        try {
            Map<String, PendingIssue> all = db.getAll();
            for (PendingIssue issue : all.values()) {
                if (issue != null && issue.getStatus() == PendingIssue.IssueStatus.PENDING) {
                    count++;
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to count pending issues: %s", e.getMessage());
        }
        return count;
    }

    private void notifyCountChange() {
        if (countChangeListener != null) {
            countChangeListener.onPendingCountChanged(getPendingCount());
        }
    }

    private void toastNewIssue(String label) {
        if (context == null) return;
        String msg = context.getString(R.string.new_pending_issue, label);
        ToastUtils.showInfo(context, msg);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
