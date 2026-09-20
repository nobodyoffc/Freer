package com.fc.freer.im.handler;

import android.content.Context;

import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.data.fcData.ContentType;
import com.fc.fc_ajdk.data.fcData.DeliveryMethod;
import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImMessageBody;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.fc.fc_ajdk.data.fcData.RequestType;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.ConsensusDocHelper;
import com.fc.freer.im.KeyLedger;
import com.fc.freer.im.MessageQueue.SendResult;
import com.fc.freer.im.PendingIssue;
import com.fc.freer.im.PendingIssueManager;
import com.fc.freer.im.SealedRow;
import com.fc.freer.im.SymkeyStore;
import com.fc.freer.manager.DatabaseManager;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.apipData.Filter;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for Team messaging.
 * Teams are on-chain with owner-managed membership.
 * Messages are encrypted with team symkey.
 */
public class TeamHandler extends BaseHandler {
    
    private final Map<String, Team> teamCache = new HashMap<>();
    private final LocalDB<Team> teamsDb;
    private PendingIssueManager pendingIssueManager;

    /** Set by ImManager once the pending-issue store for this FID exists. */
    public void setPendingIssueManager(PendingIssueManager manager) {
        this.pendingIssueManager = manager;
    }
    
    public TeamHandler(Context context, String liveFid) {
        super(context, liveFid);
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.teamsDb = dbManager.getEntityDatabase(liveFid, Team.class, Team.class);
        loadTeamsIntoCache();
    }
    
    private void loadTeamsIntoCache() {
        try {
            Map<String, Team> stored = teamsDb.getAll();
            for (Team team : stored.values()) {
                // Migration: teams saved before onChain field existed have onChain==null
                // but are confirmed on chain if birthHeight is set.
                if (team.getOnChain() == null && team.getBirthHeight() != null) {
                    team.setOnChain(true);
                    teamsDb.put(team.getId(), team);
                }
            }
            teamCache.putAll(stored);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load teams from local DB: %s", e.getMessage());
        }
    }
    
    @Override
    public ImType getType() {
        return ImType.TEAM;
    }
    
    @Override
    public SendResult send(ImMessage message) {
        if (message == null || message.getTargetId() == null) {
            notifyError("Invalid message or target team");
            return SendResult.FAIL_PERMANENT;
        }
        
        String teamId = message.getTargetId();
        
        if (symkeyStore == null || !symkeyStore.hasSymkey(teamId)) {
            notifyError("No symkey for team: " + teamId);
            return SendResult.FAIL_PERMANENT;
        }
        
        if (fapiClient == null) {
            notifyError("FAPI client not available");
            return SendResult.FAIL_PERMANENT;
        }
        
        try {
            byte[] symkey = symkeyStore.getSymkey(teamId);
            if (symkey == null) {
                notifyError("Failed to get symkey for team");
                return SendResult.FAIL_PERMANENT;
            }
            
            // Seal on a copy: the caller's object keeps its plaintext for
            // notifySent and the local transcript. Both payload sections go
            // inside the seal together -- v1 sealed `content` only and left an
            // inline binary readable by the DOCK.
            ImMessage outgoing = ImMessage.fromJson(message.toJson());
            if (outgoing == null
                    || !ImMessageBody.sealWithSymkey(outgoing, symkey, symkeyStore.getCurrentVersion(teamId))) {
                notifyError("Failed to seal team message");
                return SendResult.FAIL_PERMANENT;
            }
            message.setSymkeyVersion(outgoing.getSymkeyVersion());

            byte[] data = signedWire(outgoing);
            if (data == null) {
                notifyError("Cannot sign or encode team message");
                return SendResult.FAIL_PERMANENT;
            }
            
            java.util.List<String> recipients = new java.util.ArrayList<>();
            recipients.add(teamId);

            // Prefer sending directly to the team's DOCK (cheaper, no relay hop)
            OrgDockInfo orgDock = getOrgDockClient("team", teamId);
            if (orgDock != null) {
                DockItem dockItem = orgDock.client.dockPut(data, recipients, null, null, "IM");
                if (dockItem != null && dockItem.getId() != null) {
                    message.setDockId(dockItem.getId());
                    message.setDeliveryMethod(DeliveryMethod.DOCK_STORED);
                    message.setStatus(MessageStatus.SENT);
                    TimberLogger.d(TAG, "Team message sent directly to team's DOCK");
                    notifySent(message);
                    return SendResult.SUCCESS;
                }
                notifyError("Failed to store message in team's DOCK");
                return SendResult.RETRY_TRANSIENT;
            }

            // Fallback: resolve team's DOCK URL and forward via user's own DOCK
            String targetDockUrl = null;
            Team cachedTeam = teamCache.get(teamId);
            if (cachedTeam == null) {
                cachedTeam = loadTeamInfo(teamId);
            }
            Map<String, String> teamHome = (cachedTeam != null) ? cachedTeam.getHome() : null;
            if (teamHome != null) {
                targetDockUrl = fapiClient.getHomeServiceResolver()
                        .resolveDockFromHome(teamHome, fapiClient);
            }
            if (targetDockUrl == null) {
                try {
                    targetDockUrl = fapiClient.resolveDockUrl(teamId, "team");
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to resolve team DOCK URL: %s", e.getMessage());
                }
            }

            // A team's messages must land on the team's own DOCK so every member
            // fetches them from the same server. If we cannot resolve a target
            // DOCK, sending via our own DOCK would store the message where only we
            // can read it and falsely report "sent". Fail loudly instead.
            if (targetDockUrl == null) {
                notifyError(context.getString(hasDockConfigured(teamHome)
                        ? R.string.im_dock_unresolvable
                        : R.string.im_dock_not_configured));
                return SendResult.FAIL_PERMANENT;
            }

            if (dockAvailabilityChecker != null
                    && !dockAvailabilityChecker.isDockReachable(targetDockUrl)) {
                notifyError("Cannot reach team's DOCK server: " + targetDockUrl);
                return SendResult.RETRY_TRANSIENT;
            }

            FapiClient dockClient = ApiCenter.getInstance().getClient(ApiCenter.ConnectionRole.DOCK);
            if (dockClient == null) {
                notifyError("DOCK service not available. Configure DOCK in home settings.");
                return SendResult.RETRY_TRANSIENT;
            }
            DockItem dockItem = dockClient.dockPut(data, recipients, null, targetDockUrl, "IM");

            if (dockItem != null && dockItem.getId() != null) {
                message.setDockId(dockItem.getId());
                message.setDeliveryMethod(DeliveryMethod.DOCK_STORED);
                message.setStatus(MessageStatus.SENT);
                TimberLogger.d(TAG, "Team message forwarded via own DOCK to: %s", targetDockUrl);
                notifySent(message);
                return SendResult.SUCCESS;
            }
            
            notifyError("Failed to store message in DOCK");
            return SendResult.RETRY_TRANSIENT;
            
        } catch (Exception e) {
            notifyError("Failed to send team message: " + e.getMessage());
            return SendResult.RETRY_TRANSIENT;
        }
    }
    
    @Override
    public void handleIncoming(ImMessage message) {
        if (message == null) return;

        String teamId = message.getTargetId();
        
        // Open if sealed
        if (message.isSealed()) {
            Long version = message.getSymkeyVersion();
            long wanted = version != null ? version : 1L;

            if (symkeyStore != null && symkeyStore.hasSymkey(teamId, wanted)) {
                if (openSealed(message, teamId, wanted)) {
                    message.setBody(null);
                } else {
                    TimberLogger.w(TAG, "Failed to open team message %s", message.getId());
                    message.setContent(SealedRow.placeholder(wanted));
                }
            } else {
                message.setContent(SealedRow.placeholder(wanted));
                if (symkeyRequester != null) {
                    // The version this message needs, not "the current one"
                    // (FIMP4V3 §7.4 step 1), and put to its *sender*, who
                    // provably holds that key -- the owner may have rotated
                    // past it, or have left.
                    symkeyRequester.requestSymkey(teamId, wanted, message.getSenderId());
                }
            }
        }
        
        // Mark as unread if from someone else
        message.setUnread(!liveFid.equals(message.getSenderId()));
        message.setDeliveredAt(System.currentTimeMillis());
        
        // Notify listener
        notifyReceived(message);
    }
    
    /**
     * Encrypt content with symkey.
     */
    private String encrypt(String content, byte[] symkey) {
        try {
            return Encryptor.encryptBySymkeyToJson(content.getBytes(), symkey);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Encryption failed: %s", e.getMessage());
            return null;
        }
    }
    
    /**
     * Decrypt content with symkey.
     */
    private String decrypt(String cipher, byte[] symkey) {
        try {
            return new String(new Decryptor().decrypt(cipher, symkey).getData());
        } catch (Exception e) {
            TimberLogger.e(TAG, "Decryption failed: %s", e.getMessage());
            return null;
        }
    }
    
    /**
     * Answer a {@code SYMKEY} request -- FIMP4V3 §5.1 and §5.2, now one path.
     *
     * <p>The content names zero versions ("whatever you hold now"), one, or many; the same
     * parser reads all three, so {@code SYMKEY_HISTORY} is an accepted alias rather than a
     * second code path. One reply per version, all carrying the request's id.
     *
     * <p><b>A named version is answered with that version or not at all.</b> Substituting our
     * current key is worse than silence: it looks like a successful exchange, the requester
     * stores a key it very likely already held, and the messages it cannot read stay unreadable
     * with nothing to show why. Silence leaves the request outstanding for a member who does
     * hold it. Equally, versions we lack are simply <b>absent</b> from a batch answer: a batch
     * of eight where we hold five is five keys the asker needs, and refusing because of the
     * other three would leave them with none.
     *
     * @return true if at least one version was shared
     */
    public boolean handleSymkeyRequest(ImMessage requestMsg) {
        if (requestMsg == null || requestMsg.getContent() == null) return false;
        if (fapiClient == null || symkeyStore == null) return false;

        String requesterFid = requestMsg.getSenderId();
        if (requesterFid == null) return false;

        SymkeyStore.Asked asked = SymkeyStore.parseRequest(requestMsg.getContent());
        if (asked == null) return false;
        String teamId = asked.entityId;

        if (!isMember(teamId, liveFid)) {
            TimberLogger.d(TAG, "Ignoring symkey request for team %s: not a member", teamId);
            return false;
        }

        Team team = loadTeamInfo(teamId);
        if (team == null) {
            TimberLogger.w(TAG, "Cannot handle symkey request: team %s not found", teamId);
            return false;
        }

        // One membership check for the whole request: it is a property of the
        // asker, not of any version.
        if (!isMember(teamId, requesterFid)) {
            TimberLogger.w(TAG, "Symkey request denied for team %s: %s is not a member", teamId, requesterFid);
            if (keyLedger != null) {
                keyLedger.recordSent(teamId, 0L, requesterFid,
                        KeyLedger.Outcome.NOT_A_MEMBER, true, requestMsg.getRequestId());
            }
            return false;
        }

        List<Long> wanted = asked.wantsCurrent()
                ? (symkeyStore.hasSymkey(teamId)
                        ? List.of(symkeyStore.getCurrentVersion(teamId)) : List.of())
                : asked.versions;

        if (wanted.isEmpty()) {
            TimberLogger.d(TAG, "No symkey to share for team %s", teamId);
            if (keyLedger != null) {
                keyLedger.recordSent(teamId, 0L, requesterFid,
                        KeyLedger.Outcome.NOT_HELD, true, requestMsg.getRequestId());
            }
            return false;
        }

        int shared = 0;
        for (Long version : wanted) {
            // The cap counts versions *answered*, not named, so a request
            // padded with versions we lack cannot buy a larger answer.
            if (shared >= SymkeyStore.MAX_VERSIONS_PER_REQUEST) break;
            if (version == null) continue;
            if (!symkeyStore.hasSymkey(teamId, version)) {
                TimberLogger.d(TAG, "Asked for team %s symkey v%d, which is not held", teamId, version);
                if (keyLedger != null) {
                    keyLedger.recordSent(teamId, version, requesterFid,
                            KeyLedger.Outcome.NOT_HELD, true, requestMsg.getRequestId());
                }
                continue;
            }
            if (shareVersion(teamId, version, requesterFid, requestMsg.getRequestId())) shared++;
        }
        TimberLogger.i(TAG, "Shared %d of %d asked symkey versions for team %s with %s",
                shared, wanted.size(), teamId, requesterFid);
        return shared > 0;
    }

    /** Seal one version to a member and send it. Every outcome, including a refusal, is logged. */
    private boolean shareVersion(String teamId, long version, String requesterFid, String requestId) {
        try {
            String memberPubkey = fapiClient.getPubkey(requesterFid);
            if (memberPubkey == null) {
                TimberLogger.w(TAG, "Cannot get pubkey for requester %s", requesterFid);
                if (keyLedger != null) {
                    keyLedger.recordSent(teamId, version, requesterFid,
                            KeyLedger.Outcome.NO_PUBKEY, requestId != null, requestId);
                }
                return false;
            }

            String cipher = symkeyStore.createShareCipher(teamId, version, memberPubkey);
            if (cipher == null) {
                TimberLogger.w(TAG, "Failed to create share cipher for team %s v%d", teamId, version);
                if (keyLedger != null) {
                    keyLedger.recordSent(teamId, version, requesterFid,
                            KeyLedger.Outcome.NOT_HELD, requestId != null, requestId);
                }
                return false;
            }

            String symkeyData = teamId + ":" + cipher;
            ImMessage shareMsg = ImMessage.createSymkey(
                    ImType.P2P, liveFid, requesterFid, symkeyData, version);
            if (requestId != null) {
                shareMsg.setRequestId(requestId);
            }

            if (p2pSender != null) {
                com.fc.freer.im.MessageQueue.SendResult result = p2pSender.sendP2p(shareMsg);
                TimberLogger.i(TAG, "Shared symkey v%d for team %s with member %s (result=%s)", version, teamId, requesterFid, result);
                boolean sent = result == com.fc.freer.im.MessageQueue.SendResult.SUCCESS;
                if (sent && keyLedger != null) {
                    keyLedger.recordSent(teamId, version, requesterFid,
                            KeyLedger.Outcome.SHARED, requestId != null, requestId);
                }
                return sent;
            } else {
                TimberLogger.w(TAG, "No P2P sender available for symkey share");
                return false;
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to share symkey with %s: %s", requesterFid, e.getMessage());
            return false;
        }
    }

    /**
     * Store a symkey somebody has handed us for a team.
     *
     * <p>Nothing about the sender decides whether it is stored -- that was settled before this
     * is called, by §4.2's rule in ImManager. Here there is no overwrite and no owner flag: a
     * key at a version already held is either the same key (a no-op) or a second candidate.
     *
     * @return what became of it, or null when this is not a team we know
     */
    public SymkeyStore.StoreResult handleSymkeyShare(ImMessage message) {
        if (message == null || message.getContent() == null) return null;

        String content = message.getContent();
        int separatorIndex = content.indexOf(':');
        if (separatorIndex <= 0) return null;

        String entityId = content.substring(0, separatorIndex);
        String cipher = content.substring(separatorIndex + 1);
        long version = message.getSymkeyVersion() != null ? message.getSymkeyVersion() : 1L;

        String senderFid = message.getSenderId();
        if (senderFid == null || loadTeamInfo(entityId) == null) return null;

        if (symkeyStore == null) return null;

        SymkeyStore.StoreResult result = symkeyStore.receiveSharedSymkey(entityId, version, cipher);
        TimberLogger.d(TAG, "Symkey v%d for team %s from %s: %s", version, entityId, senderFid, result);
        return result;
    }
    
    /**
     * Fetch new messages for a team from DOCK using dock.fetch (inline data).
     */
    public List<ImMessage> fetchTeamMessages(String teamId, Long since) {
        if (fapiClient == null) return null;
        
        try {
            java.util.List<String> recipientIds = java.util.Collections.singletonList(teamId);
            com.fc.fc_ajdk.data.apipData.Fcdsl fcdsl = new com.fc.fc_ajdk.data.apipData.Fcdsl();
            fcdsl.setSize("50");
            
            com.fc.fc_ajdk.fapi.message.FapiResponse response = fapiClient.dockFetch(recipientIds, fcdsl);
            if (response == null || !response.isSuccess()) return null;
            
            List<DockItem> items = com.fc.fc_ajdk.utils.ObjectUtils.objectToList(
                    response.getData(), DockItem.class);
            if (items == null) return null;
            
            java.util.List<ImMessage> messages = new java.util.ArrayList<>();
            
            for (DockItem item : items) {
                if (since != null && item.getCreateTime() != null && item.getCreateTime() <= since) {
                    continue;
                }
                if (item.getDataBase64() == null) continue;
                
                try {
                    byte[] data = android.util.Base64.decode(item.getDataBase64(), android.util.Base64.NO_WRAP);
                    ImMessage msg = ImMessage.fromWireBytes(data);
                    if (msg != null) {
                        msg.setDockId(item.getId());
                        msg.setDeliveryMethod(DeliveryMethod.DOCK_STORED);
                        handleIncoming(msg);
                        messages.add(msg);
                    }
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to parse team message: %s", e.getMessage());
                }
            }
            
            return messages;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to fetch team messages: %s", e.getMessage());
            return null;
        }
    }
    
    /**
     * Load team info. Returns from local cache/DB first, fetches from chain if missing.
     */
    public Team loadTeamInfo(String teamId) {
        if (teamCache.containsKey(teamId)) {
            return teamCache.get(teamId);
        }
        
        if (fapiClient == null) return null;
        
        try {
            Map<String, Team> result = fapiClient.entityByIds("team", Team.class, List.of(teamId));
            Team team = result.get(teamId);
            if (team != null) {
                team.setOnChain(true);
                saveOnChainTeam(team);
            }
            return team;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load team info: %s", e.getMessage());
            return null;
        }
    }
    
    private void saveTeam(Team team) {
        teamCache.put(team.getId(), team);
        teamsDb.put(team.getId(), team);
    }
    
    /**
     * Save team info from public sync (confirmed on chain).
     */
    public void saveTeamPublic(Team team) {
        if (team == null || team.getId() == null) return;
        team.setOnChain(true);
        saveOnChainTeam(team);
    }

    /**
     * Store a team as fetched from chain, first reconciling this FID's consensus obligation
     * against it. Every on-chain read funnels through here; {@link #savePendingTeam} deliberately
     * does not, since a locally-edited team is not evidence of what the chain says.
     */
    private void saveOnChainTeam(Team team) {
        checkConsensusObligation(teamCache.get(team.getId()), team);
        saveTeam(team);
    }

    /**
     * Raise or clear this FID's "sign the new consensus" issue for a team.
     * <p>
     * The chain is the source of truth: a member owes a signature exactly while the team lists
     * them in {@code notAgreeMembers}, which the indexer refills with every non-owner member each
     * time the owner carves a different {@code consensusId}. Reading it here means the obligation
     * surfaces however the team was refreshed, and it also clears itself when the signature was
     * sent from another device or the member was dismissed.
     * <p>
     * {@code cached} is the copy about to be overwritten — the only place the <i>previous</i>
     * consensus id still exists, since the chain only ever holds the current one. It is recorded
     * on the issue so the member can read what they agreed to before, alongside what they are
     * being asked to agree to now.
     */
    private void checkConsensusObligation(Team cached, Team fresh) {
        if (pendingIssueManager == null || fresh == null || fresh.getId() == null) return;
        try {
            List<String> notAgreed = fresh.getNotAgreeMembers();
            boolean owes = notAgreed != null && notAgreed.contains(liveFid);

            if (!owes) {
                pendingIssueManager.clearConsensusIssuesForTeam(fresh.getId(),
                        PendingIssue.IssueStatus.ACCEPTED);
                return;
            }
            if (fresh.getConsensusId() == null) return;

            String oldConsensusId = null;
            if (cached != null && cached.getConsensusId() != null
                    && !cached.getConsensusId().equals(fresh.getConsensusId())) {
                oldConsensusId = cached.getConsensusId();
            }
            pendingIssueManager.addConsensusChangeIssue(fresh.getId(), fresh.getStdName(),
                    fresh.getOwner(), oldConsensusId, fresh.getConsensusId(),
                    ConsensusDocHelper.resolveTeamDiskSid(fresh));
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to check consensus obligation: %s", e.getMessage());
        }
    }

    /**
     * Save a locally-created pending team (TX broadcast, not yet confirmed).
     * Sets onChain=null to mark it as pending.
     */
    public void savePendingTeam(Team team) {
        if (team == null || team.getId() == null) return;
        team.setOnChain(null);
        saveTeam(team);
    }

    /**
     * Return all teams with onChain==null (TX submitted, awaiting confirmation).
     */
    public List<Team> getPendingTeams() {
        List<Team> pending = new java.util.ArrayList<>();
        for (Team team : teamCache.values()) {
            if (team.getOnChain() == null) {
                pending.add(team);
            }
        }
        return pending;
    }
    
    /**
     * Check if user is a member of the team.
     */
    public boolean isMember(String teamId, String fid) {
        Team team = loadTeamInfo(teamId);
        if (team == null || team.getMembers() == null || fid == null) return false;
        for (String memberId : team.getMembers()) {
            if (fid.equals(memberId))
                return true;
        }
        return false;
    }
    
    /**
     * Check if user is the owner of the team.
     */
    public boolean isOwner(String teamId, String fid) {
        Team team = loadTeamInfo(teamId);
        if (team == null) return false;
        return fid.equals(team.getOwner());
    }
    
    /**
     * Create and distribute symkey for a team (owner only).
     * Generates a new versioned symkey and distributes it to all team members
     * by encrypting it with each member's public key and sending via DOCK.
     */
    public boolean createSymkey(String teamId) {
        if (!isOwner(teamId, liveFid)) {
            notifyError("Only team owner can create symkey");
            return false;
        }
        
        if (symkeyStore == null) return false;

        // Named by the clock, floored above every version known here -- §1. A counter could
        // not survive one owner being signed in on two devices, which is by design.
        SymkeyStore.Minted minted = symkeyStore.mintSymkey(teamId);
        if (minted == null) {
            notifyError("Failed to generate symkey");
            return false;
        }

        distributeSymkey(teamId, minted.version);
        TimberLogger.d(TAG, "Created symkey for team %s version %d", teamId, minted.version);
        return true;
    }
    
    /**
     * Distribute the symkey to all team members via P2P.
     * Each member receives the symkey encrypted with their public key.
     * Uses p2pSender to properly resolve each recipient's DOCK URL.
     */
    private void distributeSymkey(String teamId, long version) {
        Team team = loadTeamInfo(teamId);
        if (team == null || team.getMembers() == null) return;
        if (fapiClient == null || symkeyStore == null) return;
        
        for (String memberFid : team.getMembers()) {
            if (memberFid.equals(liveFid)) continue;
            
            try {
                String memberPubkey = fapiClient.getPubkey(memberFid);
                if (memberPubkey == null) {
                    TimberLogger.w(TAG, "Cannot get pubkey for member %s, skipping symkey distribution", memberFid);
                    if (keyLedger != null) {
                        keyLedger.recordSent(teamId, version, memberFid,
                                KeyLedger.Outcome.NO_PUBKEY, false, null);
                    }
                    continue;
                }
                
                String cipher = symkeyStore.createShareCipher(teamId, version, memberPubkey);
                if (cipher == null) continue;

                String symkeyData = teamId + ":" + cipher;
                ImMessage shareMsg = ImMessage.createSymkey(
                        ImType.P2P,
                        liveFid,
                        memberFid,
                        symkeyData,
                        version
                );
                
                if (p2pSender != null) {
                    com.fc.freer.im.MessageQueue.SendResult result = p2pSender.sendP2p(shareMsg);
                    TimberLogger.d(TAG, "Distributed symkey v%d for team %s to %s (result=%s)",
                            version, teamId, memberFid, result);
                    // The owner's fan-out is how most keys in a team ever travel, so a ledger
                    // recording only answers to requests would miss the majority of its own
                    // subject. Unsolicited, because nobody asked.
                    if (result == com.fc.freer.im.MessageQueue.SendResult.SUCCESS && keyLedger != null) {
                        keyLedger.recordSent(teamId, version, memberFid,
                                KeyLedger.Outcome.SHARED, false, null);
                    }
                } else {
                    TimberLogger.w(TAG, "No P2P sender available for symkey distribution to %s", memberFid);
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to distribute symkey to %s: %s", memberFid, e.getMessage());
            }
        }
    }
    
    /**
     * Get cached team or fetch.
     */
    public Team getTeam(String teamId) {
        return teamCache.getOrDefault(teamId, loadTeamInfo(teamId));
    }
    
    /**
     * Get all locally cached teams.
     */
    public List<Team> getAllTeams() {
        return new java.util.ArrayList<>(teamCache.values());
    }
    
    /**
     * Refresh teams updated on-chain since the given height.
     * Queries FAPI for teams where liveFid is a member and lastHeight > lastUpdateHeight.
     *
     * @return the maximum lastHeight among returned teams, or lastUpdateHeight if none found
     */
    public long refreshUpdatedTeams(FapiClient client, String memberFid, long lastUpdateHeight) {
        if (client == null || memberFid == null) return lastUpdateHeight;

        try {
            Fcdsl fcdsl = new Fcdsl();
            Filter filter = new Filter();
            filter.addNewTerms().addNewFields("members").addNewValues(memberFid);
            filter.addNewRange().addNewFields("lastHeight").addGt(String.valueOf(lastUpdateHeight));
            fcdsl.setFilter(filter);
            fcdsl.setSize("200");

            List<Team> teams = client.teamSearch(fcdsl);
            if (teams == null || teams.isEmpty()) return lastUpdateHeight;

            long maxHeight = lastUpdateHeight;
            for (Team team : teams) {
                saveOnChainTeam(team);
                if (team.getLastHeight() != null && team.getLastHeight() > maxHeight) {
                    maxHeight = team.getLastHeight();
                }
            }
            TimberLogger.i(TAG, "Refreshed %d updated teams (lastHeight > %d, new max=%d)",
                    teams.size(), lastUpdateHeight, maxHeight);
            return maxHeight;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to refresh updated teams: %s", e.getMessage());
            return lastUpdateHeight;
        }
    }

    /**
     * Clear in-memory cache and reload from local DB.
     */
    public void clearCache() {
        teamCache.clear();
        loadTeamsIntoCache();
    }
}
