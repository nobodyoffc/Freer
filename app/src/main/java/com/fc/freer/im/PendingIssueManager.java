package com.fc.freer.im;

import android.content.Context;

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
    private final ContactPolicy contactPolicy;

    private ImManager imManager;
    private FudpNode fudpNode;
    private CountChangeListener countChangeListener;

    private final Set<String> knownPeerFids = ConcurrentHashMap.newKeySet();

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

    private void loadKnownPeers() {
        try {
            Map<String, PendingIssue> all = db.getAll();
            for (PendingIssue issue : all.values()) {
                if (issue != null && issue.getPeerFid() != null) {
                    knownPeerFids.add(issue.getPeerFid());
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

        if (contactPolicy.isWhitelisted(peerFid) || contactPolicy.isContact(peerFid)) {
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
     * Add a team invite issue. Returns true if a new issue was created.
     */
    public boolean addTeamInviteIssue(String senderFid, String teamId, String teamName, String txId) {
        if (senderFid == null || teamId == null) return false;

        PendingIssue issue = PendingIssue.createTeamInvite(senderFid, teamId, teamName, txId);
        db.put(issue.getId(), issue);
        notifyCountChange();
        toastNewIssue(context.getString(R.string.team_invite_request));
        TimberLogger.i(TAG, "Team invite issue created: team=%s from=%s", teamId, senderFid);
        return true;
    }

    /**
     * Add a team transfer issue. Returns true if a new issue was created.
     */
    public boolean addTeamTransferIssue(String senderFid, String teamId, String teamName, String txId) {
        if (senderFid == null || teamId == null) return false;

        PendingIssue issue = PendingIssue.createTeamTransfer(senderFid, teamId, teamName, txId);
        db.put(issue.getId(), issue);
        notifyCountChange();
        toastNewIssue(context.getString(R.string.team_transfer_request));
        TimberLogger.i(TAG, "Team transfer issue created: team=%s from=%s", teamId, senderFid);
        return true;
    }

    /**
     * Accept a team invite/transfer issue.
     */
    public void acceptTeamIssue(String issueId) {
        PendingIssue issue = db.get(issueId);
        if (issue == null) return;

        issue.setStatus(PendingIssue.IssueStatus.ACCEPTED);
        issue.setResolvedAt(System.currentTimeMillis());
        db.put(issue.getId(), issue);
        notifyCountChange();

        TimberLogger.i(TAG, "Team issue accepted: %s", issueId);
    }

    /**
     * Reject a team invite/transfer issue.
     */
    public void rejectTeamIssue(String issueId) {
        PendingIssue issue = db.get(issueId);
        if (issue == null) return;

        issue.setStatus(PendingIssue.IssueStatus.REJECTED);
        issue.setResolvedAt(System.currentTimeMillis());
        db.put(issue.getId(), issue);
        notifyCountChange();

        TimberLogger.i(TAG, "Team issue rejected: %s", issueId);
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

    /**
     * Defer a team issue for later decision.
     */
    public void deferIssue(String issueId) {
        PendingIssue issue = db.get(issueId);
        if (issue == null) return;

        issue.setStatus(PendingIssue.IssueStatus.PENDING);
        db.put(issue.getId(), issue);

        TimberLogger.i(TAG, "Team issue deferred: %s", issueId);
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
            contactPolicy.addToWhitelist(fid);
            if (imManager != null) {
                TalkPartner partner = new TalkPartner();
                partner.setFid(fid);
                partner.setAddedAt(System.currentTimeMillis());
                if (issue.getPeerAlias() != null) {
                    partner.setCid(issue.getPeerAlias());
                }
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
            contactPolicy.addToBlacklist(fid);
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
