package com.fc.freer.im;

import static com.fc.fc_ajdk.constants.FieldNames.ROOM;
import static com.fc.fc_ajdk.constants.FieldNames.SQUARE;
import static com.fc.fc_ajdk.constants.FieldNames.TEAM;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.data.fcData.ContentType;
import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.fc.fc_ajdk.data.fcData.RequestType;
import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.fc_ajdk.data.fcData.TalkPartner;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.dock.DockFetchScheduler;
import com.fc.freer.im.dock.DockItemRouter;
import com.fc.freer.im.dock.DockServiceRegistry;
import com.fc.freer.im.handler.BaseHandler;
import com.fc.freer.im.handler.SquareHandler;
import com.fc.freer.im.handler.P2pHandler;
import com.fc.freer.im.handler.RoomHandler;
import com.fc.freer.im.handler.TeamHandler;
import com.fc.freer.manager.DatabaseManager;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.data.DataSyncManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.ExportMeta;
import com.fc.freer.manager.HatManager;
import com.fc.freer.model.Setting;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main manager for all instant messaging operations.
 * Coordinates P2P, Group, Team, and Room handlers.
 */
public class ImManager implements BaseHandler.MessageListener, MessageQueue.MessageSender {
    private static final String TAG = "ImManager";
    private static final String MESSAGES_DB = "im_messages";
    private static final String CONVERSATIONS_DB = "im_conversations";
    private static final String TALK_PARTNERS_DB = "im_talk_partners";

    static final int MAX_QUARANTINE_PER_STRANGER = 20;
    static final int MAX_STRANGER_PEERS = 50;
    static final long QUARANTINE_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
    
    private final Context context;
    private final String liveFid;
    private byte[] userPrikey;
    
    // Database
    private LocalDB<ImMessage> messagesDb;
    private LocalDB<Conversation> conversationsDb;
    private LocalDB<TalkPartner> talkPartnersDb;
    
    // Handlers
    private P2pHandler p2pHandler;
    private SquareHandler squareHandler;
    private TeamHandler teamHandler;
    private RoomHandler roomHandler;
    
    // Services
    private SymkeyStore symkeyStore;
    private MessageQueue messageQueue;
    private ContactPolicy contactPolicy;
    
    // DOCK auto-fetch
    private DockServiceRegistry dockRegistry;
    private DockFetchScheduler dockScheduler;
    private DockItemRouter dockRouter;
    
    // Network
    private FudpNode fudpNode;
    private FapiClient fapiClient;
    
    // Threading
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    // Listeners (multi-listener, thread-safe)
    private final List<ImListener> listeners = new CopyOnWriteArrayList<>();
    
    // Stranger peer detection
    private PendingIssueManager pendingIssueManager;
    private StrangerPeerCallback strangerPeerCallback;
    private ChannelSetupCallback channelSetupCallback;

    private boolean initialized = false;
    private volatile boolean channelConfigured = false;
    private volatile boolean channelSetupDismissed = false;

    // DOCK registration: between broadcasting the home.DOCK TX and its on-chain
    // confirmation, IM stays disabled but the setup dialog must be suppressed.
    // We poll the on-chain Freer until DOCK appears or the window expires.
    private volatile boolean registrationPending = false;
    private Runnable dockPollRunnable;
    private static final long DOCK_POLL_INTERVAL_MS = 30_000L;       // re-check every 30s
    private static final long DOCK_REG_EXPIRY_MS = 15 * 60_000L;     // give up after 15 min
    private static final String DOCK_REG_PREFS = "im_dock_registration";
    private static final String KEY_DOCK_REG_TXID = "pending_dock_txid_";
    private static final String KEY_DOCK_REG_SENT_AT = "pending_dock_sent_at_";

    private static final long SYMKEY_REQUEST_COOLDOWN_MS = 2 * 60 * 1000; // 2 minutes
    private final Map<String, Long> symkeyRequestTimestamps = new ConcurrentHashMap<>();
    private final Set<String> pendingSymkeyNonces = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingRoomInfoNonces = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingHistoryNonces = ConcurrentHashMap.newKeySet();
    
    /**
     * Deliver a received message into the normal conversation and notify the UI.
     */
    private void deliverMessage(ImMessage message) {
        messagesDb.put(message.getId(), message);
        addToConversationIndex(message);
        updateConversation(message);
        mainHandler.post(() -> {
            for (ImListener l : listeners) l.onMessageReceived(message);
        });
    }

    /**
     * Whether a sender should be auto-accepted because they are in the local
     * contacts (address book) and the user enabled "accept strangers in Contacts".
     */
    private boolean isAutoAcceptContact(String senderId) {
        try {
            if (!P2pChatSettingsActivity.isAutoAcceptContacts(context, liveFid)) {
                return false;
            }
            com.fc.freer.manager.ContactManager cm = com.fc.freer.manager.ContactManager.getInstance();
            return cm != null && cm.checkIfFidExisted(senderId);
        } catch (Exception e) {
            TimberLogger.w(TAG, "isAutoAcceptContact failed for %s: %s", senderId, e.getMessage());
            return false;
        }
    }

    /**
     * Accept a previously-unknown sender: whitelist, add as talk partner, and
     * promote any messages already quarantined from them.
     */
    private void acceptStranger(String senderId) {
        String issueId = "STRANGER_PEER_" + senderId;
        if (pendingIssueManager != null && pendingIssueManager.getIssue(issueId) != null) {
            // accept() whitelists, adds the talk partner, promotes quarantined
            // messages and marks the pending issue resolved.
            pendingIssueManager.accept(issueId);
            return;
        }
        contactPolicy.addToWhitelist(senderId);
        TalkPartner partner = new TalkPartner();
        partner.setFid(senderId);
        partner.setAddedAt(System.currentTimeMillis());
        addTalkPartner(partner);
        promoteQuarantinedMessages(senderId);
    }

    /**
     * Surface a stranger-confirmation prompt for the given sender on whichever
     * screen is foreground: the global callback (HomeActivity) and the listener
     * callback (an open ChatActivity). The message stays quarantined until the
     * user accepts.
     */
    private void requestStrangerConfirmation(String senderId) {
        mainHandler.post(() -> {
            if (strangerPeerCallback != null) {
                strangerPeerCallback.onStrangerPeerDetected(senderId);
            }
            for (ImListener l : listeners) l.onStrangerConfirmationRequired(senderId);
        });
    }

    public interface ImListener {
        void onMessageReceived(ImMessage message);
        void onMessageSent(ImMessage message);
        void onConversationUpdated(Conversation conversation);
        void onPresenceChanged(String fid, boolean online);
        void onError(String error);
        default void onStrangerMessageReceived(String senderFid, int totalCount) {}
        /** A first message from an unknown sender needs user confirmation before the conversation starts. */
        default void onStrangerConfirmationRequired(String senderFid) {}
        default void onTeamNotificationReceived(PendingIssue issue) {}
        default void onSymkeyReceived(String entityId, long version) {}
        default void onRoomInfoReceived(String roomId, long symkeyVersion) {}
        default void onMessagesRedecrypted(String entityId, List<ImMessage> messages) {}
        default void onRoomInviteReceived(PendingIssue issue) {}
        default void onHistoryRequestReceived(String requesterFid, String imType,
                                               String targetId, Long since, Long before,
                                               ImMessage requestMsg) {}
        default void onHistoryImported(String fromFid, int count) {}
        default void onChannelNotConfigured(String suggestedDockUrl) {}
        /** Fired when home.DOCK becomes available on-chain (e.g. a pending registration confirmed). */
        default void onChannelConfigured() {}
    }

    public interface StrangerPeerCallback {
        void onStrangerPeerDetected(String peerFid);
    }

    public interface ChannelSetupCallback {
        /** Fired when home.DOCK is not configured in freer.home. */
        void onChannelNotConfigured(String suggestedDockUrl);
    }
    
    public ImManager(Context context, String liveFid) {
        this.context = context.getApplicationContext();
        this.liveFid = liveFid;
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Initialize the manager.
     */
    public void initialize(byte[] userPrikey) {
        if (initialized) return;
        
        this.userPrikey = userPrikey;
        
        // Initialize databases
        DatabaseManager dbManager = DatabaseManager.getInstance();
        messagesDb = dbManager.getEntityDatabase(liveFid, ImMessage.class, ImMessage.class);
        conversationsDb = dbManager.getEntityDatabase(liveFid, Conversation.class, Conversation.class);
        talkPartnersDb = dbManager.getEntityDatabase(liveFid, TalkPartner.class, TalkPartner.class);
        if (messagesDb == null || conversationsDb == null || talkPartnersDb == null) {
            throw new IllegalStateException("IM databases are not available");
        }
        
        // Initialize services
        symkeyStore = new SymkeyStore(context, liveFid);
        symkeyStore.setUserPrikey(userPrikey);
        
        messageQueue = new MessageQueue(context, liveFid);
        messageQueue.setMessageSender(this);
        messageQueue.setListener(new MessageQueue.QueueListener() {
            @Override
            public void onMessageQueued(ImMessage message) {
                TimberLogger.d(TAG, "Message queued: %s", message.getId());
            }
            
            @Override
            public void onMessageSent(ImMessage message) {
                handleMessageSent(message);
            }
            
            @Override
            public void onMessageFailed(ImMessage message, String reason) {
                handleMessageFailed(message, reason);
            }
        });
        
        contactPolicy = new ContactPolicy(context, liveFid);
        
        // Initialize handlers
        p2pHandler = new P2pHandler(context, liveFid);
        squareHandler = new SquareHandler(context, liveFid);
        teamHandler = new TeamHandler(context, liveFid);
        roomHandler = new RoomHandler(context, liveFid);
        
        // Wire TalkPartner lookup into P2P handler
        p2pHandler.setTalkPartnerProvider(this::getTalkPartner);
        // Let the P2P handler write a freshly-fetched recipient home back so a
        // just-registered (or moved) DOCK is persisted, not re-fetched every send.
        p2pHandler.setTalkPartnerHomeUpdater(this::updateTalkPartnerHome);
        
        // Set up handler listeners
        p2pHandler.setMessageListener(this);
        squareHandler.setMessageListener(this);
        teamHandler.setMessageListener(this);
        roomHandler.setMessageListener(this);

        // Route ROOM_INFO and SYMKEY through the queue so failed deliveries are
        // retried automatically (e.g. when the recipient's DOCK is temporarily down).
        // isRoomControlType() covers both types, so send() skips DB/conversation indexing.
        BaseHandler.P2pSender p2pSender = message -> {
            send(message);
            return MessageQueue.SendResult.SUCCESS;
        };
        teamHandler.setP2pSender(p2pSender);
        roomHandler.setP2pSender(p2pSender);

        BaseHandler.SymkeyRequester requester = this::requestTeamSymkey;
        teamHandler.setSymkeyRequester(requester);
        roomHandler.setSymkeyRequester(requester);

        roomHandler.setControlMessageSender(this::send);

        // Initialize DOCK auto-fetch components
        dockRegistry = new DockServiceRegistry(liveFid);
        dockScheduler = new DockFetchScheduler(dockRegistry, liveFid);
        dockRouter = new DockItemRouter(liveFid);
        dockRouter.setHandlers(p2pHandler, squareHandler, teamHandler, roomHandler);

        // Wire dock availability checker so group handlers verify target dock
        // connectivity before sending (prevents sending when fetch would fail)
        BaseHandler.DockAvailabilityChecker dockChecker = dockUrl -> {
            if (dockUrl == null) return false;
            if (dockRegistry.isFailed(dockUrl)) return false;
            return dockRegistry.getClientForDock(dockUrl) != null;
        };
        squareHandler.setDockAvailabilityChecker(dockChecker);
        teamHandler.setDockAvailabilityChecker(dockChecker);
        roomHandler.setDockAvailabilityChecker(dockChecker);

        p2pHandler.setDockRegistry(dockRegistry);
        squareHandler.setDockRegistry(dockRegistry);
        teamHandler.setDockRegistry(dockRegistry);
        roomHandler.setDockRegistry(dockRegistry);
        
        LocalDB<DockItem> unknownItemsDb = dbManager.getEntityDatabase(liveFid, DockItem.class, DockItem.class);
        dockRouter.setUnknownItemsDb(unknownItemsDb);
        
        dockScheduler.setCallback(new DockFetchScheduler.FetchCallback() {
            @Override
            public void onItemsFetched(List<DockItem> items, String dockUrl) {
                int count = dockRouter.routeItems(items, dockUrl);
                if (count > 0) {
                    TimberLogger.d(TAG, "Routed %d/%d dock items from %s", count, items.size(), dockUrl);
                }
            }
            
            @Override
            public void onFetchError(String dockUrl, String error) {
                TimberLogger.w(TAG, "Dock fetch error from %s: %s", dockUrl, error);
            }
        });
        
        initialized = true;
    }
    
    /**
     * Start the messaging service (after FUDP/FAPI are ready).
     */
    public void start(FudpNode fudpNode, FapiClient fapiClient) {
        this.fudpNode = fudpNode;
        this.fapiClient = fapiClient;

        // Give the registry a long-lived FUDP node so it can always re-bootstrap a
        // dock connection that died (e.g. during a long sleep), even after every
        // per-dock client has been invalidated.
        if (dockRegistry != null) {
            dockRegistry.setRecoveryNode(fudpNode);
        }
        
        // Whitelist liveFid itself so the user's own messages are never treated as stranger.
        if (contactPolicy != null && liveFid != null) {
            contactPolicy.addToWhitelist(liveFid);
            TimberLogger.d(TAG, "Whitelisted liveFid: %s", liveFid);
        }
        
        // Whitelist the FAPI server dealer so it never triggers stranger notifications.
        // The dealer only relays API responses; it does not send P2P messages.
        if (fapiClient != null && contactPolicy != null) {
            String dealerFid = fapiClient.getServicePeerId();
            if (dealerFid != null) {
                contactPolicy.addToWhitelist(dealerFid);
                TimberLogger.d(TAG, "Whitelisted FAPI server dealer: %s", dealerFid);
            }
        }
        
        // Initialize handlers with network components
        p2pHandler.initialize(fudpNode, fapiClient, symkeyStore);
        squareHandler.initialize(fudpNode, fapiClient, symkeyStore);
        teamHandler.initialize(fudpNode, fapiClient, symkeyStore);
        roomHandler.initialize(fudpNode, fapiClient, symkeyStore);
        
        if (pendingIssueManager != null) {
            pendingIssueManager.setFudpNode(fudpNode);
        }
        
        // Start message queue
        messageQueue.start();
        
        // Refresh on-chain group/team membership, then populate DOCK registry
        executor.execute(() -> {
            try {
                // Refresh teams and groups updated since last known height
                Setting setting = SettingManager.getInstance().getCurrentSetting();
                if (setting != null && fapiClient != null) {
                    long teamH = setting.getLastTeamUpdateHeight();
                    long newTeamH = teamHandler.refreshUpdatedTeams(fapiClient, liveFid, teamH);
                    if (newTeamH > teamH) setting.setLastTeamUpdateHeight(newTeamH);

                    long groupH = setting.getLastGroupUpdateHeight();
                    long newGroupH = squareHandler.refreshUpdatedSquares(fapiClient, liveFid, groupH);
                    if (newGroupH > groupH) setting.setLastGroupUpdateHeight(newGroupH);
                }

                dockRegistry.refreshAll(
                        getAllTalkPartners(),
                        roomHandler.getAllRooms(),
                        teamHandler.getAllTeams(),
                        squareHandler.getAllSquares(),
                        fapiClient
                );
                dockScheduler.start();
                // Fetch immediately while bootstrap connections are still fresh.
                // Without this, the first fetch waits 60s (NORMAL layer) and the
                // FUDP connection to the dock server may have gone idle by then.
                dockScheduler.fetchNow();
                TimberLogger.i(TAG, "DOCK scheduler started with %d entries", dockRegistry.size());

                // Resume a pending registration (suppresses the dialog + resumes
                // polling) before the first check so a restart mid-confirmation
                // doesn't re-pop the setup prompt.
                restorePendingRegistration();
                checkChannelConfigured();
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to start DOCK scheduler: %s", e.getMessage());
            }
        });
        
        // Set up FUDP event listener
        if (fudpNode != null) {
            fudpNode.setEventListener(new com.fc.fc_ajdk.fudp.node.NodeEventListener() {
                @Override
                public void onPeerConnected(String peerId, long connectionId) {
                    TimberLogger.d(TAG, "Peer connected: %s (conn=%d)", peerId, connectionId);
                    p2pHandler.updatePeerBook(peerId, null, null,
                            com.fc.fc_ajdk.data.fcData.DeliveryMethod.FUDP_DIRECT);
                    notifyPresenceChanged(peerId, true);
                }

                @Override
                public void onPeerDisconnected(String peerId, long connectionId) {
                    TimberLogger.d(TAG, "Peer disconnected: %s (conn=%d)", peerId, connectionId);
                    notifyPresenceChanged(peerId, false);
                }

                @Override
                public void onNotifyReceived(String peerId, long messageId, int dataType, byte[] data) {
                    TimberLogger.d(TAG, "FUDP NOTIFY received from %s: msgId=%d, dataType=%d", peerId, messageId, dataType);
                    p2pHandler.updatePeerBook(peerId, null, null,
                            com.fc.fc_ajdk.data.fcData.DeliveryMethod.FUDP_DIRECT);
                    notifyPresenceChanged(peerId, true);
                    // Stranger confirmation is now driven by the unified receive gate
                    // (onMessageReceived), so it behaves identically across FUDP/ROAD/DOCK.
                    handleIncomingBytes(data, peerId, messageId);
                }

                @Override
                public void onNotifyAck(String peerId, long messageId, long rttMs) {
                    TimberLogger.d(TAG, "FUDP NOTIFY_ACK from %s: msgId=%d, rtt=%dms", peerId, messageId, rttMs);
                    p2pHandler.updatePeerBook(peerId, null, null,
                            com.fc.fc_ajdk.data.fcData.DeliveryMethod.FUDP_DIRECT);
                    notifyPresenceChanged(peerId, true);
                    p2pHandler.handleNotifyAck(peerId, messageId, rttMs);
                }
                
                @Override
                public void onPingComplete(String peerId, long rttMs) {
                    TimberLogger.d(TAG, "Ping complete from %s: rtt=%dms", peerId, rttMs);
                    p2pHandler.updatePeerBook(peerId, null, null,
                            com.fc.fc_ajdk.data.fcData.DeliveryMethod.FUDP_DIRECT);
                    notifyPresenceChanged(peerId, true);
                }
            });
        }
        
        TimberLogger.i(TAG, "ImManager started for %s", liveFid);
    }

    private void checkChannelConfigured() {
        if (fapiClient == null) return;
        try {
            com.fc.fc_ajdk.data.fchData.Freer freer = fapiClient.getFreer(liveFid);
            if (freer == null) return;

            Map<String, String> home = freer.getHome();
            boolean hasDock = false;
            if (home != null) {
                for (Map.Entry<String, String> e : home.entrySet()) {
                    String key = e.getKey();
                    String val = e.getValue();
                    if (key == null || val == null || val.isEmpty()) continue;
                    if (key.startsWith("DOCK")) { hasDock = true; break; }
                }
            }

            channelConfigured = hasDock;

            if (hasDock) {
                // DOCK is live on-chain. If we were waiting on a registration TX,
                // the registration has confirmed: clear pending and let IM enable.
                if (registrationPending) {
                    clearPendingRegistration();
                    TimberLogger.i(TAG, "DOCK registration confirmed on-chain for %s", liveFid);
                    mainHandler.post(this::notifyChannelReady);
                }
                return;
            }

            // Not configured. Suppress the setup prompt while a registration TX is
            // still pending confirmation; the poll loop will keep re-checking.
            if (registrationPending) return;

            // Default the DOCK server to the current FAPI client's service ID (SID).
            String suggestedSid = (fapiClient.getApiAccount() != null)
                    ? fapiClient.getApiAccount().getProviderId() : null;
            final String finalSid = suggestedSid;
            mainHandler.post(() -> {
                if (channelSetupCallback != null) {
                    channelSetupCallback.onChannelNotConfigured(finalSid);
                }
                for (ImListener l : listeners) l.onChannelNotConfigured(finalSid);
            });
            TimberLogger.i(TAG, "No DOCK channel configured for %s, suggesting SID: %s", liveFid, suggestedSid);
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to check channel configuration: %s", e.getMessage());
        }
    }

    /**
     * Returns whether the user has registered home.DOCK or home.FUDP on-chain.
     * Without one of these, P2P chat cannot operate.
     */
    public boolean isChannelConfigured() {
        return channelConfigured;
    }

    /** Whether the user dismissed the channel-setup prompt; UI should keep P2P disabled. */
    public boolean isChannelSetupDismissed() {
        return channelSetupDismissed;
    }

    public void setChannelSetupDismissed(boolean dismissed) {
        this.channelSetupDismissed = dismissed;
    }

    /**
     * Whether a home.DOCK registration TX has been broadcast but not yet
     * confirmed on-chain. While true, the channel-setup dialog must stay
     * suppressed even though {@link #isChannelConfigured()} is still false.
     */
    public boolean isRegistrationPending() {
        return registrationPending;
    }

    /**
     * Called when a HOME-register TX carrying home.DOCK has been broadcast.
     * Enters the "registration pending" state: IM stays disabled (DOCK is not
     * on-chain until the TX confirms), the setup dialog is suppressed, and a
     * poll loop refreshes the Freer until DOCK appears or the window expires.
     */
    public void onRegistrationTxSent(String txId) {
        persistPendingRegistration(txId, System.currentTimeMillis());
        registrationPending = true;
        TimberLogger.i(TAG, "DOCK registration TX %s broadcast for %s; entering pending state", txId, liveFid);
        startDockPoll();
    }

    private SharedPreferences dockRegPrefs() {
        return context.getSharedPreferences(DOCK_REG_PREFS, Context.MODE_PRIVATE);
    }

    private void persistPendingRegistration(String txId, long sentAt) {
        if (liveFid == null) return;
        dockRegPrefs().edit()
                .putString(KEY_DOCK_REG_TXID + liveFid, txId)
                .putLong(KEY_DOCK_REG_SENT_AT + liveFid, sentAt)
                .apply();
    }

    private void clearPendingRegistration() {
        registrationPending = false;
        if (dockPollRunnable != null) {
            mainHandler.removeCallbacks(dockPollRunnable);
        }
        if (liveFid == null) return;
        dockRegPrefs().edit()
                .remove(KEY_DOCK_REG_TXID + liveFid)
                .remove(KEY_DOCK_REG_SENT_AT + liveFid)
                .apply();
    }

    private long pendingRegistrationSentAt() {
        if (liveFid == null) return 0L;
        return dockRegPrefs().getLong(KEY_DOCK_REG_SENT_AT + liveFid, 0L);
    }

    /**
     * On startup, resume a pending DOCK registration that hasn't expired so a
     * restart during the confirmation window doesn't re-pop the setup dialog.
     * Expired records are cleared. Runs on the executor (callers hold it).
     */
    private void restorePendingRegistration() {
        long sentAt = pendingRegistrationSentAt();
        if (sentAt <= 0L) {
            registrationPending = false;
            return;
        }
        if (System.currentTimeMillis() - sentAt >= DOCK_REG_EXPIRY_MS) {
            TimberLogger.i(TAG, "Persisted DOCK registration for %s expired; clearing", liveFid);
            clearPendingRegistration();
            return;
        }
        registrationPending = true;
        TimberLogger.i(TAG, "Resuming pending DOCK registration for %s", liveFid);
        startDockPoll();
    }

    /** (Re)start the single poll loop that re-checks the on-chain DOCK state. */
    private void startDockPoll() {
        mainHandler.post(() -> {
            if (dockPollRunnable != null) {
                mainHandler.removeCallbacks(dockPollRunnable);
            }
            dockPollRunnable = () -> {
                if (!registrationPending) return;
                long sentAt = pendingRegistrationSentAt();
                if (sentAt <= 0L || System.currentTimeMillis() - sentAt >= DOCK_REG_EXPIRY_MS) {
                    TimberLogger.i(TAG, "DOCK registration poll for %s expired without confirmation", liveFid);
                    clearPendingRegistration();
                    // Re-check normally so the setup dialog can return if still unconfigured.
                    executor.execute(this::checkChannelConfigured);
                    return;
                }
                executor.execute(() -> {
                    checkChannelConfigured();
                    if (registrationPending) {
                        mainHandler.postDelayed(dockPollRunnable, DOCK_POLL_INTERVAL_MS);
                    }
                });
            };
            mainHandler.postDelayed(dockPollRunnable, DOCK_POLL_INTERVAL_MS);
        });
    }

    private void notifyChannelReady() {
        for (ImListener l : listeners) l.onChannelConfigured();
    }

    public boolean isReady() {
        return initialized && messagesDb != null && conversationsDb != null && talkPartnersDb != null;
    }

    /** Whether a FapiClient is currently attached to the handlers. */
    public boolean hasFapiClient() {
        return fapiClient != null;
    }

    /**
     * Re-attach a FapiClient to all handlers when the current one is missing.
     * <p>
     * An ImManager can be built (via {@link #start}) before the FAPI client has
     * finished (re)connecting — e.g. right after {@code ApiCenter.initiate()} on
     * waking from a long sleep or a process restore, when
     * {@code ApiCenter.getClient()} still returns null. Because {@link #isReady()}
     * does not depend on the FAPI client, that half-built manager is cached and
     * every send fails with "FAPI client not available" until the app is killed
     * and relaunched. Calling this once a client is available heals the manager
     * in place so it never gets stuck in that state.
     *
     * @return true if a FapiClient is now attached
     */
    public synchronized boolean ensureFapiClient(FapiClient client) {
        if (fapiClient != null) return true;
        if (client == null) return false;

        this.fapiClient = client;

        // Whitelist the FAPI server dealer so it never triggers stranger notifications.
        if (contactPolicy != null) {
            String dealerFid = client.getServicePeerId();
            if (dealerFid != null) {
                contactPolicy.addToWhitelist(dealerFid);
            }
        }

        // Re-initialize handlers with the now-available client.
        p2pHandler.initialize(fudpNode, client, symkeyStore);
        squareHandler.initialize(fudpNode, client, symkeyStore);
        teamHandler.initialize(fudpNode, client, symkeyStore);
        roomHandler.initialize(fudpNode, client, symkeyStore);

        TimberLogger.i(TAG, "Re-attached FapiClient to handlers for %s", liveFid);

        // Populate the DOCK registry now that resolution is possible (no-op if empty).
        refreshDockRegistry();

        return true;
    }

    /**
     * Stop the messaging service.
     */
    public void stop() {
        if (dockScheduler != null) {
            dockScheduler.stop();
        }
        if (messageQueue != null) messageQueue.stop();
        if (symkeyStore != null) symkeyStore.clearCache();
        if (p2pHandler != null) p2pHandler.setMessageListener(null);
        if (squareHandler != null) squareHandler.setMessageListener(null);
        if (teamHandler != null) teamHandler.setMessageListener(null);
        if (roomHandler != null) roomHandler.setMessageListener(null);
        
        TimberLogger.i(TAG, "ImManager stopped for %s", liveFid);
    }
    
    // ========== Listener Management ==========

    /**
     * Add a listener. Multiple listeners can be registered simultaneously.
     */
    public void addListener(ImListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Remove a previously registered listener.
     */
    public void removeListener(ImListener listener) {
        listeners.remove(listener);
    }

    /**
     * Convenience: clear all listeners and set a single one (backward compat).
     */
    public void setListener(ImListener listener) {
        listeners.clear();
        if (listener != null) {
            listeners.add(listener);
        }
    }
    
    public void setStrangerPeerCallback(StrangerPeerCallback callback) {
        this.strangerPeerCallback = callback;
    }

    public void setChannelSetupCallback(ChannelSetupCallback callback) {
        this.channelSetupCallback = callback;
    }
    
    public PendingIssueManager getPendingIssueManager() {
        return pendingIssueManager;
    }

    public void setPendingIssueManager(PendingIssueManager pim) {
        this.pendingIssueManager = pim;
        if (pim != null) {
            pim.setContactPolicy(this.contactPolicy);
            pim.setImManager(this);
            if (this.fudpNode != null) {
                pim.setFudpNode(this.fudpNode);
            }
        }
    }
    
    // ========== Send Messages ==========
    
    /**
     * Send a text message.
     */
    public void sendText(ImType type, String targetId, String text) {
        ImMessage message = ImMessage.createText(type, liveFid, targetId, text);
        send(message);
    }
    
    /**
     * Send a message.
     * Assigns a globally unique ID from the FUDP layer if not already set.
     */
    public void send(ImMessage message) {
        if (message == null) return;
        
        if (!message.hasFudpId() && fudpNode != null) {
            message.setIdFromLong(fudpNode.generateMessageId());
        }
        message.setSenderId(liveFid);
        
        boolean isReceipt = message.getContentType() == ContentType.RECEIPT;

        if (!isReceipt && !isRoomControlType(message.getContentType())
                && !isTeamNotification(message)) {
            messagesDb.put(message.getId(), message);
            addToConversationIndex(message);
            updateConversation(message);

            // Initiating a P2P conversation is implicit acceptance of the peer:
            // whitelist them so their replies are delivered without a separate
            // stranger-confirmation prompt on our side. An explicit block wins,
            // so a blacklisted peer is not silently re-accepted by messaging them.
            if (message.getType() == ImType.P2P) {
                String peer = message.getTargetId();
                if (peer != null && contactPolicy != null
                        && !contactPolicy.isBlacklisted(peer)
                        && !contactPolicy.isWhitelisted(peer)) {
                    contactPolicy.addToWhitelist(peer);
                    TimberLogger.d(TAG, "Whitelisted P2P peer on outgoing message: %s", peer);
                }
            }
        }

        messageQueue.enqueue(message);
    }
    
    /**
     * Retry a failed message.
     */
    public void retryMessage(String messageId) {
        messageQueue.retryNow(messageId);
    }

    // ========== Team Notification ==========

    public static final String TEAM_INVITE_PREFIX = "[TEAM_INVITE]";
    public static final String TEAM_TRANSFER_PREFIX = "[TEAM_TRANSFER]";

    /** Send an informational P2P message notifying the recipient they have been invited. */
    public void sendTeamInviteNotification(String targetFid, String teamId, String teamName) {
        if (targetFid == null || teamId == null) return;
        String name = (teamName != null && !teamName.isEmpty()) ? teamName : teamId;
        sendText(ImType.P2P, targetFid, TEAM_INVITE_PREFIX + teamId + "|" + name);
        TimberLogger.i(TAG, "Sent team invite notification to %s: team=%s", targetFid, teamId);
    }

    /** Send an informational P2P message notifying the recipient that a team is being transferred to them. */
    public void sendTeamTransferNotification(String targetFid, String teamId, String teamName) {
        if (targetFid == null || teamId == null) return;
        String name = (teamName != null && !teamName.isEmpty()) ? teamName : teamId;
        sendText(ImType.P2P, targetFid, TEAM_TRANSFER_PREFIX + teamId + "|" + name);
        TimberLogger.i(TAG, "Sent team transfer notification to %s: team=%s", targetFid, teamId);
    }
    
    // ========== Symkey Request / Response ==========

    /**
     * Handle incoming SYMKEY-related messages (requests and pushes).
     * @return true if the message was handled and should not be processed further
     */
    private boolean handleSymkeyMessage(ImMessage message) {
        if (message == null) return false;

        ContentType ct = message.getContentType();

        if (ct == ContentType.REQUEST && message.getRequestType() == RequestType.SYMKEY) {
            TimberLogger.i(TAG, "Received symkey request from %s: %s", message.getSenderId(), message.getContent());
            boolean handled = false;
            if (teamHandler != null) {
                handled = teamHandler.handleSymkeyRequest(message);
            }
            if (!handled && roomHandler != null) {
                handled = roomHandler.handleSymkeyRequest(message);
            }
            if (!handled) {
                TimberLogger.d(TAG, "Symkey request not handled (not a member or no key)");
            }
            return true;
        }

        if (ct == ContentType.REQUEST && message.getRequestType() == RequestType.ROOM_INFO) {
            TimberLogger.i(TAG, "Received room info request from %s: %s", message.getSenderId(), message.getContent());
            boolean handled = false;
            if (roomHandler != null) {
                handled = roomHandler.handleRoomInfoRequest(message);
            }
            if (!handled) {
                TimberLogger.d(TAG, "Room info request not handled (not a member or no room)");
            }
            return true;
        }

        if (ct == ContentType.SYMKEY) {
            String nonce = message.getRequestId();
            String senderFid = message.getSenderId();

            String symkeyEntityId = null;
            String content = message.getContent();
            if (content != null) {
                int colonIdx = content.indexOf(':');
                symkeyEntityId = colonIdx > 0 ? content.substring(0, colonIdx) : content;
            }

            boolean isFromOwner = false;
            if (symkeyEntityId != null && senderFid != null) {
                if (roomHandler != null) {
                    Room room = roomHandler.getRoom(symkeyEntityId);
                    if (room != null) {
                        isFromOwner = room.isOwner(senderFid);
                    }
                }
                if (!isFromOwner && teamHandler != null) {
                    Team team = teamHandler.getTeam(symkeyEntityId);
                    if (team != null) {
                        isFromOwner = senderFid.equals(team.getOwner());
                    }
                }
            }

            if (!isFromOwner) {
                if (nonce == null || !pendingSymkeyNonces.contains(nonce)) {
                    TimberLogger.d(TAG, "Ignoring symkey from non-owner %s without matching request nonce", senderFid);
                    return true;
                }
            }

            TimberLogger.i(TAG, "Received symkey push from %s (nonce=%s)", senderFid, nonce);
            boolean handled = false;
            if (teamHandler != null) {
                handled = teamHandler.handleSymkeyShare(message);
            }
            if (!handled && roomHandler != null) {
                handled = roomHandler.handleSymkeyShare(message);
            }
            if (handled) {
                if (nonce != null) {
                    pendingSymkeyNonces.remove(nonce);
                }
                if (symkeyEntityId != null) {
                    String finalEntityId = symkeyEntityId;
                    symkeyRequestTimestamps.keySet().removeIf(k -> k.startsWith(finalEntityId + ":"));
                    redecryptPendingMessages(finalEntityId);
                    long symkeyVersion = symkeyStore != null ? symkeyStore.getCurrentVersion(finalEntityId) : 0L;
                    mainHandler.post(() -> {
                        for (ImListener l : listeners) l.onSymkeyReceived(finalEntityId, symkeyVersion);
                    });
                }
            }
            return true;
        }

        return false;
    }

    /**
     * Re-decrypt messages that previously failed decryption due to missing symkey.
     * Scans the conversation index for the entity, finds messages with cipher still present
     * and placeholder content, decrypts them with the now-available symkey, and updates the DB.
     */
    private void redecryptPendingMessages(String entityId) {
        if (entityId == null || symkeyStore == null || messagesDb == null) return;

        ImType entityType = null;
        if (teamHandler != null && teamHandler.getTeam(entityId) != null) {
            entityType = ImType.TEAM;
        } else if (roomHandler != null && roomHandler.getRoom(entityId) != null) {
            entityType = ImType.ROOM;
        }
        if (entityType == null) return;

        String convKey = conversationIndexKey(entityType, entityId);
        List<String> index = messagesDb.getAllFromList(convKey);
        if (index == null || index.isEmpty()) return;

        List<ImMessage> redecrypted = new ArrayList<>();

        for (String msgId : index) {
            ImMessage msg = messagesDb.get(msgId);
            if (msg == null) continue;
            if (msg.getCipher() == null) continue;
            if (msg.getContent() != null && !msg.getContent().startsWith("[Encrypted")) continue;

            Long version = msg.getSymkeyVersion();
            byte[] symkey = symkeyStore.getSymkey(entityId, version != null ? version : 1L);
            if (symkey == null) continue;

            try {
                String content = new String(new Decryptor().decrypt(msg.getCipher(), symkey).getData());
                if (content != null) {
                    msg.setContent(content);
                    messagesDb.put(msg.getId(), msg);
                    redecrypted.add(msg);
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Re-decrypt failed for message %s: %s", msgId, e.getMessage());
            }
        }

        if (!redecrypted.isEmpty()) {
            TimberLogger.i(TAG, "Re-decrypted %d messages for entity %s", redecrypted.size(), entityId);
            mainHandler.post(() -> {
                for (ImListener l : listeners) l.onMessagesRedecrypted(entityId, redecrypted);
            });
        }
    }

    /**
     * Handle incoming ROOM_INFO messages.
     * Delegates to RoomHandler to create/update the local Room and store symkey.
     * @return true if the message was a ROOM_INFO and was handled
     */
    private boolean handleRoomInfoMessage(ImMessage message) {
        if (message == null) return false;
        if (message.getContentType() != ContentType.ROOM_INFO) return false;

        String senderFid = message.getSenderId();
        TimberLogger.i(TAG, "Received ROOM_INFO from %s", senderFid);

        boolean isFromOwner = false;
        if (message.getContent() != null) {
            try {
                com.fc.fc_ajdk.data.fcData.RoomInfo ri =
                        com.fc.fc_ajdk.data.fcData.RoomInfo.fromJson(message.getContent());
                if (ri != null && senderFid != null && senderFid.equals(ri.getOwner())) {
                    isFromOwner = true;
                }
            } catch (Exception ignored) {}
        }

        String nonce = message.getRequestId();
        if (!isFromOwner) {
            if (nonce == null || !pendingRoomInfoNonces.contains(nonce)) {
                TimberLogger.d(TAG, "Ignoring ROOM_INFO from non-owner %s without matching request nonce", senderFid);
                return true;
            }
        }

        if (roomHandler != null) {
            boolean handled = roomHandler.handleRoomInfoShare(message);
            if (handled) {
                if (nonce != null) {
                    pendingRoomInfoNonces.remove(nonce);
                }
                Room room = roomHandler.getRoom(message.getTargetId());
                if (room == null && message.getContent() != null) {
                    try {
                        com.fc.fc_ajdk.data.fcData.RoomInfo ri =
                                com.fc.fc_ajdk.data.fcData.RoomInfo.fromJson(message.getContent());
                        if (ri != null && ri.getId() != null) {
                            room = roomHandler.getRoom(ri.getId());
                        }
                    } catch (Exception ignored) {}
                }
                if (room != null) {
                    ensureRoomConversation(room);
                    String entityId = room.getId();
                    long roomSymkeyVersion = symkeyStore != null ? symkeyStore.getCurrentVersion(entityId) : 0L;
                    mainHandler.post(() -> {
                        for (ImListener l : listeners) l.onRoomInfoReceived(entityId, roomSymkeyVersion);
                    });
                }
                refreshDockRegistry();
                mainHandler.post(() -> {
                    for (ImListener l : listeners) l.onMessageReceived(message);
                });
            } else if (isFromOwner && message.getContent() != null) {
                createRoomInvitePendingIssue(senderFid, message.getContent());
            }
        }
        return true;
    }

    /**
     * Handle incoming ROOM_LEAVE messages.
     * Delegates to RoomHandler so the owner can remove the member and broadcast updates.
     * @return true if the message was a ROOM_LEAVE and was handled
     */
    private boolean handleRoomLeaveMessage(ImMessage message) {
        if (message == null) return false;
        if (message.getContentType() != ContentType.ROOM_LEAVE) return false;

        String senderFid = message.getSenderId();
        TimberLogger.i(TAG, "Received ROOM_LEAVE from %s", senderFid);

        if (roomHandler != null) {
            boolean handled = roomHandler.handleRoomLeaveMessage(message);
            if (handled) {
                String roomId = message.getContent();
                Room room = roomHandler.getRoom(roomId);
                if (room != null) {
                    ensureRoomConversation(room);
                }
                if (senderFid != null) {
                    addRoomSystemMessage(roomId, context.getString(R.string.member_left_room, senderFid));
                }
                refreshDockRegistry();
            }
            return true;
        }
        return false;
    }

    /**
     * Handle incoming ROOM_ACCEPT messages.
     * Only the room owner acts on these: the pending member is marked confirmed.
     * @return true if the message was a ROOM_ACCEPT (always consumed)
     */
    private boolean handleRoomAcceptMessage(ImMessage message) {
        if (message == null) return false;
        if (message.getContentType() != ContentType.ROOM_ACCEPT) return false;

        String senderFid = message.getSenderId();
        TimberLogger.i(TAG, "Received ROOM_ACCEPT from %s", senderFid);

        if (roomHandler != null) {
            boolean confirmed = roomHandler.handleRoomAcceptMessage(message);
            if (confirmed) {
                String roomId = message.getContent();
                Room room = roomHandler.getRoom(roomId);
                if (room != null) {
                    ensureRoomConversation(room);
                }
                addRoomSystemMessage(roomId, context.getString(R.string.member_joined_room, senderFid));
            }
        }
        return true;
    }

    /**
     * Handle incoming ROOM_DISBAND messages (P2P or via the room channel).
     * Only valid from the room owner: deactivates the room locally and
     * marks the conversation as left.
     * @return true if the message was a ROOM_DISBAND (always consumed)
     */
    private boolean handleRoomDisbandMessage(ImMessage message) {
        if (message == null) return false;
        if (message.getContentType() != ContentType.ROOM_DISBAND) return false;

        String senderFid = message.getSenderId();
        TimberLogger.i(TAG, "Received ROOM_DISBAND from %s", senderFid);

        if (roomHandler != null) {
            boolean handled = roomHandler.handleRoomDisbandMessage(message);
            if (handled) {
                String roomId = message.getContent();
                addRoomSystemMessage(roomId, context.getString(R.string.room_disbanded_system));
                markRoomConversationAsLeft(roomId);
                refreshDockRegistry();
            }
        }
        return true;
    }

    /**
     * Handle incoming ROOM_REMOVED messages.
     * Only valid from the room owner: this user was removed from the room,
     * so it is deactivated locally and the conversation marked as left.
     * @return true if the message was a ROOM_REMOVED (always consumed)
     */
    private boolean handleRoomRemovedMessage(ImMessage message) {
        if (message == null) return false;
        if (message.getContentType() != ContentType.ROOM_REMOVED) return false;

        String senderFid = message.getSenderId();
        TimberLogger.i(TAG, "Received ROOM_REMOVED from %s", senderFid);

        if (roomHandler != null) {
            boolean handled = roomHandler.handleRoomRemovedMessage(message);
            if (handled) {
                String roomId = message.getContent();
                addRoomSystemMessage(roomId, context.getString(R.string.removed_from_room_system));
                markRoomConversationAsLeft(roomId);
                refreshDockRegistry();
            }
        }
        return true;
    }

    /**
     * Transport-only messages: never stored as chat messages or shown in conversation lists.
     * Includes all ROOM control signals plus ROOM_INFO and SYMKEY distribution messages.
     */
    private static boolean isRoomControlType(ContentType ct) {
        return ct == ContentType.ROOM_LEAVE || ct == ContentType.ROOM_ACCEPT
                || ct == ContentType.ROOM_DISBAND || ct == ContentType.ROOM_REMOVED
                || ct == ContentType.ROOM_INFO || ct == ContentType.SYMKEY;
    }

    /**
     * Team invite/transfer notifications sent as ephemeral TEXT messages.
     * They should not be stored on the sender side (the recipient stores them on receipt).
     */
    private static boolean isTeamNotification(ImMessage message) {
        if (message == null || message.getContentType() != ContentType.TEXT) return false;
        String content = message.getContent();
        return content != null
                && (content.startsWith(TEAM_INVITE_PREFIX) || content.startsWith(TEAM_TRANSFER_PREFIX));
    }

    /**
     * Mark a room conversation as left so it disappears from the active room list.
     */
    public void markRoomConversationAsLeft(String roomId) {
        if (roomId == null || conversationsDb == null) return;
        String convId = ImType.ROOM.name() + "_" + roomId;
        Conversation conv = conversationsDb.get(convId);
        if (conv != null) {
            conv.setLeftGroup(true);
            conversationsDb.put(convId, conv);
        }
    }

    private void addRoomSystemMessage(String roomId, String text) {
        if (roomId == null || text == null) return;

        ImMessage sysMsg = new ImMessage();
        sysMsg.setId(UUID.randomUUID().toString());
        sysMsg.setType(ImType.ROOM);
        sysMsg.setSenderId(null);
        sysMsg.setTargetId(roomId);
        sysMsg.setContentType(ContentType.TEXT);
        sysMsg.setContent(text);
        sysMsg.setTimestamp(System.currentTimeMillis());
        sysMsg.setStatus(MessageStatus.DELIVERED);

        messagesDb.put(sysMsg.getId(), sysMsg);
        addToConversationIndex(sysMsg);
        updateConversation(sysMsg);

        mainHandler.post(() -> {
            for (ImListener l : listeners) l.onMessageReceived(sysMsg);
        });
    }

    /**
     * Create a PendingIssue for a new room invitation.
     * Called when handleRoomInfoShare() returns false (room not in DB yet).
     */
    private void createRoomInvitePendingIssue(String senderFid, String roomInfoJson) {
        if (pendingIssueManager == null || roomInfoJson == null) return;

        try {
            com.fc.fc_ajdk.data.fcData.RoomInfo ri =
                    com.fc.fc_ajdk.data.fcData.RoomInfo.fromJson(roomInfoJson);
            if (ri == null || ri.getId() == null) return;

            String roomName = ri.getName() != null ? ri.getName() : ri.getId();
            boolean created = pendingIssueManager.addRoomInviteIssue(
                    senderFid, ri.getId(), roomName, roomInfoJson);

            if (created) {
                PendingIssue issue = PendingIssue.createRoomInvite(
                        senderFid, ri.getId(), roomName, roomInfoJson);
                mainHandler.post(() -> {
                    for (ImListener l : listeners) l.onRoomInviteReceived(issue);
                });
                TimberLogger.i(TAG, "Created room invite pending issue for room %s from %s",
                        ri.getId(), senderFid);
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to create room invite issue: %s", e.getMessage());
        }
    }

    /**
     * Handle an accepted room invite from PendingIssueManager.
     * Creates the room from the stored roomInfo JSON, stores symkey, and sets up conversation.
     */
    public void handleAcceptedRoomInvite(String roomInfoJson) {
        if (roomHandler == null || roomInfoJson == null) return;

        Room room = roomHandler.acceptRoomInvite(roomInfoJson);
        if (room != null) {
            ensureRoomConversation(room);
            refreshDockRegistry();
            String entityId = room.getId();
            long inviteSymkeyVersion = symkeyStore != null ? symkeyStore.getCurrentVersion(entityId) : 0L;
            mainHandler.post(() -> {
                for (ImListener l : listeners) l.onRoomInfoReceived(entityId, inviteSymkeyVersion);
            });
        }
    }

    /**
     * Handle a rejected room invite from PendingIssueManager.
     * Sends a ROOM_LEAVE to the owner so the owner removes this FID.
     */
    public void handleRejectedRoomInvite(String roomInfoJson) {
        if (roomHandler == null || roomInfoJson == null) return;
        roomHandler.rejectRoomInvite(roomInfoJson);
    }

    /**
     * Check if a target FID has any reachable channels (FUDP/ROAD/DOCK) in their home map.
     */
    private boolean hasReachableChannels(String fid) {
        if (fapiClient == null) return false;
        try {
            com.fc.fc_ajdk.data.fchData.Freer freer = fapiClient.getFreer(fid);
            if (freer == null || freer.getHome() == null || freer.getHome().isEmpty()) {
                return false;
            }
            for (String key : freer.getHome().keySet()) {
                if (key != null && (key.startsWith("DOCK") || key.startsWith("ROAD") || key.startsWith("FUDP"))) {
                    String val = freer.getHome().get(key);
                    if (val != null && !val.isEmpty()) return true;
                }
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to check channels for %s: %s", fid, e.getMessage());
        }
        return false;
    }

    private boolean isSymkeyRequestOnCooldown(String entityId, String targetFid) {
        String key = entityId + ":" + targetFid;
        Long lastTime = symkeyRequestTimestamps.get(key);
        if (lastTime != null && System.currentTimeMillis() - lastTime < SYMKEY_REQUEST_COOLDOWN_MS) {
            return true;
        }
        symkeyRequestTimestamps.put(key, System.currentTimeMillis());
        return false;
    }

    private String generateSymkeyNonce() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Send a symkey request to the entity owner (team or room).
     * Called when a member enters an encrypted chat without the symkey.
     */
    public void requestTeamSymkey(String entityId) {
        if (entityId == null) return;
        executor.execute(() -> {
            String ownerFid = null;

            if (teamHandler != null) {
                Team team = teamHandler.loadTeamInfo(entityId);
                if (team != null) ownerFid = team.getOwner();
            }

            if (ownerFid == null && roomHandler != null) {
                com.fc.fc_ajdk.data.fcData.Room room = roomHandler.getRoom(entityId);
                if (room != null) ownerFid = room.getOwner();
            }

            if (ownerFid == null) {
                TimberLogger.w(TAG, "Cannot request symkey: entity %s or owner not found", entityId);
                return;
            }

            if (liveFid.equals(ownerFid)) {
                TimberLogger.d(TAG, "Skipping symkey request: current user is the owner");
                return;
            }

            SymkeyStore store = getSymkeyStore();
            if (store != null && store.hasSymkey(entityId)) {
                TimberLogger.d(TAG, "Symkey already available for %s", entityId);
                return;
            }

            if (isSymkeyRequestOnCooldown(entityId, ownerFid)) {
                TimberLogger.d(TAG, "Symkey request for %s to %s on cooldown, skipping", entityId, ownerFid);
                return;
            }

            if (!hasReachableChannels(ownerFid)) {
                TimberLogger.w(TAG, "Owner %s has no reachable channels, skipping symkey request", ownerFid);
                String msg = context.getString(R.string.owner_no_reachable_channels);
                mainHandler.post(() -> {
                    for (ImListener l : listeners) l.onError(msg);
                });
                return;
            }

            String nonce = generateSymkeyNonce();
            ImMessage request = ImMessage.createRequest(
                    ImType.P2P, liveFid, ownerFid,
                    RequestType.SYMKEY,
                    entityId + ":latest"
            );
            request.setRequestId(nonce);

            MessageQueue.SendResult result = p2pHandler.send(request);
            if (result == MessageQueue.SendResult.SUCCESS) {
                pendingSymkeyNonces.add(nonce);
                TimberLogger.i(TAG, "Sent symkey request for %s to owner %s (nonce=%s)", entityId, ownerFid, nonce);
            } else {
                TimberLogger.w(TAG, "Failed to send symkey request for %s to owner %s: %s", entityId, ownerFid, result);
            }
        });
    }

    /**
     * Send symkey requests to specific members (for manual "Ask Symkey" flow).
     * Works for both team and room entities.
     */
    public void requestSymkeyFromMembers(String entityId, List<String> targetFids) {
        if (entityId == null || targetFids == null || targetFids.isEmpty()) return;
        if (fapiClient == null) return;

        executor.execute(() -> {
            int sent = 0;
            List<String> unreachable = new ArrayList<>();

            for (String targetFid : targetFids) {
                if (targetFid.equals(liveFid)) continue;

                if (isSymkeyRequestOnCooldown(entityId, targetFid)) {
                    TimberLogger.d(TAG, "Symkey request for %s to %s on cooldown, skipping", entityId, targetFid);
                    continue;
                }

                if (!hasReachableChannels(targetFid)) {
                    TimberLogger.w(TAG, "Member %s has no reachable channels, skipping", targetFid);
                    unreachable.add(targetFid);
                    continue;
                }

                String nonce = generateSymkeyNonce();
                ImMessage request = ImMessage.createRequest(
                        ImType.P2P, liveFid, targetFid,
                        RequestType.SYMKEY,
                        entityId + ":latest"
                );
                request.setRequestId(nonce);

                MessageQueue.SendResult result = p2pHandler.send(request);
                if (result == MessageQueue.SendResult.SUCCESS) {
                    pendingSymkeyNonces.add(nonce);
                    TimberLogger.i(TAG, "Sent symkey request for %s to member %s (nonce=%s)", entityId, targetFid, nonce);
                    sent++;
                } else {
                    TimberLogger.w(TAG, "Failed to send symkey request to %s: %s", targetFid, result);
                }
            }

            if (!unreachable.isEmpty()) {
                String msg = context.getString(R.string.members_no_reachable_channels, unreachable.size());
                mainHandler.post(() -> {
                    for (ImListener l : listeners) l.onError(msg);
                });
            }
        });
    }

    // ========== Message Queue.MessageSender Implementation ==========
    
    @Override
    public MessageQueue.SendResult sendMessage(ImMessage message) {
        if (message == null || message.getType() == null) return MessageQueue.SendResult.FAIL_PERMANENT;
        
        return switch (message.getType()) {
            case P2P -> p2pHandler.send(message);
            case SQUARE -> squareHandler.send(message);
            case TEAM -> teamHandler.send(message);
            case ROOM -> roomHandler.send(message);
        };
    }
    
    // ========== Handler.MessageListener Implementation ==========
    
    @Override
    public void onMessageReceived(ImMessage message) {
        executor.execute(() -> {
            if (handleHistoryMessage(message)) {
                return;
            }

            if (handleSymkeyMessage(message)) {
                return;
            }

            if (handleRoomInfoMessage(message)) {
                return;
            }

            if (handleRoomLeaveMessage(message)) {
                return;
            }

            if (handleRoomAcceptMessage(message)) {
                return;
            }

            if (handleRoomDisbandMessage(message)) {
                return;
            }

            if (handleRoomRemovedMessage(message)) {
                return;
            }

            String senderId = message.getSenderId();

            // Fix 1: Dedup self-sent group messages already stored by send()
            if (liveFid != null && liveFid.equals(senderId)) {
                ImMessage existing = messagesDb.get(message.getId());
                if (existing != null) {
                    if (existing.getStatus() == MessageStatus.SENT
                            || existing.getStatus() == MessageStatus.PENDING) {
                        existing.setStatus(MessageStatus.DELIVERED);
                        existing.setDeliveredAt(System.currentTimeMillis());
                        messagesDb.put(existing.getId(), existing);
                        mainHandler.post(() -> {
                            for (ImListener l : listeners) l.onMessageSent(existing);
                        });
                    }
                    return;
                }
            }

            // Fix 4: Null sender (malformed message) bypasses contact policy
            if (senderId == null) {
                deliverMessage(message);
                return;
            }

            // Dedup: skip messages already in the DB (defence against re-delivery
            // when the DOCK cursor resets after a transient network error).
            if (messagesDb.get(message.getId()) != null) {
                TimberLogger.d(TAG, "Skipping duplicate incoming message: %s", message.getId());
                return;
            }

            // Group channels (SQUARE/TEAM/ROOM) are not subject to the stranger
            // strategy: SQUARE is open, and TEAM/ROOM membership already implies
            // the user accepted the conversation. Only P2P goes through the gate.
            if (message.getType() != ImType.P2P) {
                deliverMessage(message);
                return;
            }

            // Unified receive gate — same behaviour across FUDP/ROAD/DOCK, since
            // every channel funnels through here.

            // Established / accepted partner — deliver directly.
            if (contactPolicy.isWhitelisted(senderId)) {
                deliverMessage(message);
                return;
            }

            // Explicitly blocked — drop silently.
            if (contactPolicy.isBlacklisted(senderId)) {
                return;
            }

            // Address-book contact with auto-accept enabled — accept and deliver.
            if (isAutoAcceptContact(senderId)) {
                acceptStranger(senderId);
                deliverMessage(message);
                return;
            }

            // Unknown sender — hold the message and require user confirmation
            // before starting a new conversation.
            boolean firstContact = pendingIssueManager == null
                    || pendingIssueManager.getIssue("STRANGER_PEER_" + senderId) == null;
            quarantineMessage(message);

            // Surface the confirmation prompt once per stranger (on first contact),
            // unless the user chose to reject strangers outright (CONTACTS_ONLY /
            // ACCEPT_NONE), in which case the message stays held as a message
            // request (badge only, no prompt).
            if (firstContact && contactPolicy.getStrategy() == ContactPolicy.Strategy.ACCEPT_ALL) {
                requestStrangerConfirmation(senderId);
            }
        });
    }
    
    @Override
    public void onMessageSent(ImMessage message) {
        handleMessageSent(message);
    }
    
    @Override
    public void onDeliveryStatusChanged(String messageId, MessageStatus status) {
        executor.execute(() -> {
            ImMessage message = messagesDb.get(messageId);
            if (message != null) {
                message.setStatus(status);
                if (status == MessageStatus.DELIVERED) {
                    message.setDeliveredAt(System.currentTimeMillis());
                } else if (status == MessageStatus.READ) {
                    message.setReadAt(System.currentTimeMillis());
                }
                messagesDb.put(message.getId(), message);
                
                mainHandler.post(() -> {
                    for (ImListener l : listeners) l.onMessageSent(message);
                });
            }
        });
    }
    
    @Override
    public void onError(String error) {
        mainHandler.post(() -> {
            for (ImListener l : listeners) l.onError(error);
        });
    }
    
    // ========== Quarantine ==========

    /**
     * Store a stranger's message in quarantine instead of the regular conversation.
     */
    private void quarantineMessage(ImMessage message) {
        String senderId = message.getSenderId();
        int existing = countQuarantinedMessages(senderId);
        if (existing >= MAX_QUARANTINE_PER_STRANGER) {
            TimberLogger.d(TAG, "Quarantine limit reached for %s, dropping message", senderId);
            return;
        }

        message.setStatus(MessageStatus.QUARANTINED);
        message.setUnread(true);
        messagesDb.put(message.getId(), message);
        addToQuarantineIndex(message);

        int newCount = existing + 1;
        if (pendingIssueManager != null) {
            pendingIssueManager.updateStrangerMessageCount(senderId, message.getContent());
        }

        TimberLogger.d(TAG, "Message quarantined from %s (count=%d)", senderId, newCount);
        mainHandler.post(() -> {
            for (ImListener l : listeners) l.onStrangerMessageReceived(senderId, newCount);
        });
    }

    /**
     * Promote all quarantined messages from a sender into normal conversations.
     * Called when the user accepts a stranger peer issue.
     */
    public void promoteQuarantinedMessages(String senderFid) {
        executor.execute(() -> {
            List<ImMessage> quarantined = getQuarantinedMessages(senderFid, 0);
            if (quarantined.isEmpty()) return;

            for (ImMessage msg : quarantined) {
                msg.setStatus(MessageStatus.DELIVERED);
                msg.setDeliveredAt(System.currentTimeMillis());
                messagesDb.put(msg.getId(), msg);
                addToConversationIndex(msg);
                updateConversation(msg);
            }

            removeQuarantineIndex(senderFid);
            TimberLogger.i(TAG, "Promoted %d quarantined messages from %s", quarantined.size(), senderFid);

            mainHandler.post(() -> {
                for (ImMessage msg : quarantined) {
                    for (ImListener l : listeners) l.onMessageReceived(msg);
                }
            });
        });
    }

    /**
     * Delete all quarantined messages from a sender.
     * Called when the user rejects a stranger peer issue.
     */
    public void purgeQuarantinedMessages(String senderFid) {
        executor.execute(() -> {
            List<ImMessage> quarantined = getQuarantinedMessages(senderFid, 0);
            for (ImMessage msg : quarantined) {
                messagesDb.remove(msg.getId());
            }
            removeQuarantineIndex(senderFid);
            TimberLogger.i(TAG, "Purged %d quarantined messages from %s", quarantined.size(), senderFid);
        });
    }

    // ========== Quarantine Index ==========

    private String quarantineIndexKey(String senderFid) {
        return "quar_" + senderFid;
    }

    private void addToQuarantineIndex(ImMessage message) {
        if (message == null || message.getId() == null || message.getSenderId() == null) return;

        String key = quarantineIndexKey(message.getSenderId());
        if (!messagesDb.getListNames().contains(key)) {
            messagesDb.createList(key, String.class);
        }

        List<String> existing = messagesDb.getAllFromList(key);
        if (existing == null || !existing.contains(message.getId())) {
            messagesDb.addToList(key, message.getId());
        }
    }

    private void removeQuarantineIndex(String senderFid) {
        String key = quarantineIndexKey(senderFid);
        if (messagesDb.getListNames().contains(key)) {
            messagesDb.clearList(key);
        }
    }

    /**
     * Get quarantined messages from a sender.
     * @param limit max messages (0 = unlimited)
     */
    public List<ImMessage> getQuarantinedMessages(String senderFid, int limit) {
        String key = quarantineIndexKey(senderFid);
        List<String> index = messagesDb.getAllFromList(key);
        if (index == null || index.isEmpty()) return new ArrayList<>();

        List<ImMessage> result = new ArrayList<>();
        for (String msgId : index) {
            ImMessage msg = messagesDb.get(msgId);
            if (msg != null && msg.getStatus() == MessageStatus.QUARANTINED) {
                result.add(msg);
                if (limit > 0 && result.size() >= limit) break;
            }
        }
        return result;
    }

    /**
     * Count quarantined messages from a sender.
     */
    public int countQuarantinedMessages(String senderFid) {
        String key = quarantineIndexKey(senderFid);
        long size = messagesDb.getListSize(key);
        return (int) Math.max(0, size);
    }

    // ========== Internal Handlers ==========
    
    private void notifyPresenceChanged(String fid, boolean online) {
        mainHandler.post(() -> {
            for (ImListener l : listeners) l.onPresenceChanged(fid, online);
        });
    }
    
    private void handleIncomingBytes(byte[] data, String senderFid, long fudpMessageId) {
        if (data == null || data.length == 0) return;
        
        executor.execute(() -> {
            try {
                ImMessage message = ImMessage.fromWireBytes(data);
                
                if (message == null) {
                    TimberLogger.w(TAG, "Failed to parse incoming wire bytes");
                    return;
                }
                
                if (message.getId() == null) {
                    message.setIdFromLong(fudpMessageId);
                }
                
                if (message.getType() == ImType.P2P && senderFid != null) {
                    p2pHandler.updatePeerBook(senderFid, null, null);
                }
                
                switch (message.getType()) {
                    case P2P -> p2pHandler.handleIncoming(message);
                    case SQUARE -> squareHandler.handleIncoming(message);
                    case TEAM -> teamHandler.handleIncoming(message);
                    case ROOM -> roomHandler.handleIncoming(message);
                }
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error handling incoming message: %s", e.getMessage());
            }
        });
    }
    
    private void handleMessageSent(ImMessage message) {
        if (message.getContentType() == ContentType.RECEIPT) {
            TimberLogger.d(TAG, "Receipt sent silently: id=%s, target=%s", message.getId(), message.getTargetId());
            return;
        }
        if (isRoomControlType(message.getContentType())) {
            TimberLogger.d(TAG, "Room control message sent silently: type=%s, target=%s",
                    message.getContentType(), message.getTargetId());
            return;
        }
        if (isTeamNotification(message)) {
            TimberLogger.d(TAG, "Team notification sent silently: target=%s", message.getTargetId());
            return;
        }
        
        executor.execute(() -> {
            if (message.getStatus() == null || message.getStatus() == MessageStatus.PENDING) {
                message.setStatus(MessageStatus.SENT);
            }
            messagesDb.put(message.getId(), message);
            
            if (message.getType() == ImType.P2P && message.getDeliveryMethod() != null) {
                String targetFid = message.getTargetId();
                TalkPartner partner = talkPartnersDb.get(targetFid);
                if (partner != null) {
                    partner.setLastTalkVia(message.getDeliveryMethod().name());
                    partner.setLastTalkedAt(System.currentTimeMillis());
                    talkPartnersDb.put(partner.getFid(), partner);
                }
            }
            
            mainHandler.post(() -> {
                for (ImListener l : listeners) l.onMessageSent(message);
            });
        });
    }
    
    private void handleMessageFailed(ImMessage message, String reason) {
        executor.execute(() -> {
            message.setStatus(MessageStatus.FAILED);
            if (!isRoomControlType(message.getContentType())) {
                messagesDb.put(message.getId(), message);
            }

            mainHandler.post(() -> {
                for (ImListener l : listeners) l.onError("Message failed: " + reason);
            });
        });
    }
    
    private void updateConversation(ImMessage message) {
        String convId = message.getType().name() + "_" + 
                (message.getType() == ImType.P2P 
                        ? message.getConversationPartnerId(liveFid) 
                        : message.getTargetId());
        
        Conversation conv = conversationsDb.get(convId);
        
        if (conv == null) {
            conv = Conversation.fromMessage(message, liveFid);
        } else {
            conv.updateWithMessage(message);
            if (!message.isOutgoing(liveFid)) {
                conv.incrementUnread();
            }
        }
        
        if (conv.getDisplayName() == null && message.getType() == ImType.P2P) {
            String partnerId = message.getConversationPartnerId(liveFid);
            TalkPartner partner = talkPartnersDb.get(partnerId);
            if (partner != null && partner.getCid() != null) {
                conv.setDisplayName(partner.getCid());
            }
        }
        
        conversationsDb.put(conv.getId(), conv);
        
        final Conversation finalConv = conv;
        mainHandler.post(() -> {
            for (ImListener l : listeners) l.onConversationUpdated(finalConv);
        });
    }
    
    // ========== Query Methods ==========
    
    /**
     * Get all conversations sorted by last active.
     */
    public List<Conversation> getConversations() {
        if (conversationsDb == null) {
            TimberLogger.e(TAG, "Cannot load conversations: conversationsDb is not initialized");
            return new ArrayList<>();
        }
        List<Conversation> convs = new ArrayList<>(conversationsDb.getAll().values());
        Collections.sort(convs, (a, b) -> {
            Long aTime = a.getLastActiveAt() != null ? a.getLastActiveAt() : 0L;
            Long bTime = b.getLastActiveAt() != null ? b.getLastActiveAt() : 0L;
            return bTime.compareTo(aTime);
        });
        return convs;
    }
    
    /**
     * Get messages for a conversation using indexed lookup.
     */
    public List<ImMessage> getMessages(ImType type, String targetId, int limit,
                                        Long sinceTimestamp, Long beforeTimestamp) {
        String convKey = conversationIndexKey(type, targetId);
        long indexSize = messagesDb.getListSize(convKey);
        if (indexSize <= 0) {
            return new ArrayList<>();
        }
        
        List<String> index = messagesDb.getAllFromList(convKey);
        if (index == null || index.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<ImMessage> result = new ArrayList<>();
        
        for (int i = index.size() - 1; i >= 0; i--) {
            String msgId = index.get(i);
            ImMessage msg = messagesDb.get(msgId);
            if (msg == null) continue;
            if (msg.getContentType() == ContentType.RECEIPT) continue;
            
            Long ts = msg.getTimestamp();
            if (ts == null) ts = 0L;
            
            if (sinceTimestamp != null && ts < sinceTimestamp) continue;
            if (beforeTimestamp != null && ts >= beforeTimestamp) continue;
            
            result.add(msg);
            
            if (limit > 0 && result.size() >= limit) break;
        }
        
        return result;
    }
    
    /**
     * Get unread count for a conversation.
     */
    public int getUnreadCount(ImType type, String targetId) {
        String convId = type.name() + "_" + targetId;
        Conversation conv = conversationsDb.get(convId);
        return conv != null && conv.getUnreadCount() != null ? conv.getUnreadCount() : 0;
    }
    
    /**
     * Get total unread count.
     */
    public int getTotalUnreadCount() {
        int total = 0;
        for (Conversation conv : conversationsDb.getAll().values()) {
            if (conv.getUnreadCount() != null) {
                total += conv.getUnreadCount();
            }
        }
        return total;
    }
    
    /**
     * Get total unread count for a specific ImType.
     */
    public int getUnreadCountByType(ImType type) {
        int total = 0;
        for (Conversation conv : conversationsDb.getAll().values()) {
            if (conv.getType() == type && conv.getUnreadCount() != null) {
                total += conv.getUnreadCount();
            }
        }
        return total;
    }

    /**
     * Mark conversation as read.
     */
    public void markConversationAsRead(ImType type, String targetId) {
        executor.execute(() -> {
            String convId = type.name() + "_" + targetId;
            Conversation conv = conversationsDb.get(convId);
            if (conv != null) {
                conv.markAsRead();
                conversationsDb.put(conv.getId(), conv);
            }
            
            // Fix 2: Iterate index directly instead of capped getMessages(100)
            String convKey = conversationIndexKey(type, targetId);
            List<String> index = messagesDb.getAllFromList(convKey);
            if (index != null) {
                for (int i = index.size() - 1; i >= 0; i--) {
                    ImMessage msg = messagesDb.get(index.get(i));
                    if (msg == null) continue;
                    if (msg.getUnread() == null || !msg.getUnread()) break;
                    msg.setUnread(false);
                    messagesDb.put(msg.getId(), msg);
                    if (type == ImType.P2P && !msg.isOutgoing(liveFid)) {
                        p2pHandler.markAsRead(msg);
                    }
                }
            }
        });
    }
    
    // ========== Accessor Methods ==========
    
    public P2pHandler getP2pHandler() { return p2pHandler; }
    public SquareHandler getSquareHandler() { return squareHandler; }
    public TeamHandler getTeamHandler() { return teamHandler; }
    public RoomHandler getRoomHandler() { return roomHandler; }
    
    public SymkeyStore getSymkeyStore() { return symkeyStore; }
    public ContactPolicy getContactPolicy() { return contactPolicy; }
    public MessageQueue getMessageQueue() { return messageQueue; }
    public LocalDB<Conversation> getConversationsDb() { return conversationsDb; }
    
    public String getLiveFid() { return liveFid; }
    
    // ========== Convenience Methods ==========
    
    public Square getSquare(String squareId) {
        return squareHandler.getSquare(squareId);
    }
    
    public Team getTeam(String teamId) {
        return teamHandler.getTeam(teamId);
    }
    
    public Room getRoom(String roomId) {
        return roomHandler.getRoom(roomId);
    }
    
    public List<Room> getAllRooms() {
        return roomHandler.getAllRooms();
    }
    
    public Room createRoom(String name, String desc) {
        Room room = roomHandler.createRoom(name, desc);
        if (room != null) {
            ensureRoomConversation(room);
        }
        return room;
    }

    public Room createRoom(String name, String desc, java.util.List<String> memberFids,
                           java.util.Map<String, String> home) {
        Room room = roomHandler.createRoom(name, desc, memberFids, home);
        if (room != null) {
            ensureRoomConversation(room);
            refreshDockRegistry();
        }
        return room;
    }

    private void ensureRoomConversation(Room room) {
        if (room == null || room.getId() == null) return;
        String convId = ImType.ROOM.name() + "_" + room.getId();
        Conversation conv = conversationsDb.get(convId);
        if (conv == null) {
            conv = new Conversation();
            conv.setId(convId);
            conv.setType(ImType.ROOM);
            conv.setTargetId(room.getId());
            conv.setCreatedAt(System.currentTimeMillis());
        }
        conv.setDisplayName(room.getName());
        if (room.getOwner() != null) {
            conv.setAvatarDid(room.getOwner());
        }
        conv.setMemberNum((long) room.getMemberCount());
        conv.setLastActiveAt(System.currentTimeMillis());
        conversationsDb.put(convId, conv);

        final Conversation finalConv = conv;
        mainHandler.post(() -> {
            for (ImListener l : listeners) l.onConversationUpdated(finalConv);
        });
    }

    public boolean updateRoom(String roomId, String name, String desc) {
        return roomHandler.updateRoom(roomId, name, desc);
    }

    public boolean updateRoom(String roomId, String name, String desc,
                              java.util.Map<String, String> home) {
        boolean result = roomHandler.updateRoom(roomId, name, desc, home);
        if (result && home != null) {
            refreshDockRegistry();
        }
        return result;
    }

    public boolean addRoomMember(String roomId, String memberFid) {
        return roomHandler.addMember(roomId, memberFid);
    }

    public int addRoomMembers(String roomId, java.util.List<String> memberFids) {
        return roomHandler.addMembers(roomId, memberFids);
    }

    public boolean removeRoomMember(String roomId, String memberFid) {
        return roomHandler.removeMember(roomId, memberFid);
    }

    public boolean shareRoomInfo(String roomId, String targetFid) {
        return roomHandler.shareRoomInfo(roomId, targetFid);
    }

    public void shareRoomInfoToAll(String roomId) {
        roomHandler.shareRoomInfoToAll(roomId);
    }

    /**
     * Share room info to multiple selected members (owner only).
     * @return number of members successfully shared with
     */
    public int shareRoomInfoToMembers(String roomId, List<String> targetFids) {
        if (roomHandler == null || targetFids == null) return 0;
        int count = 0;
        for (String fid : targetFids) {
            if (roomHandler.shareRoomInfo(roomId, fid)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Share symkey to multiple selected members (owner only).
     * @return number of members successfully shared with
     */
    public int shareRoomSymkeyToMembers(String roomId, List<String> targetFids) {
        if (roomHandler == null) return 0;
        return roomHandler.shareSymkeyToMembers(roomId, targetFids);
    }

    /**
     * Send room info requests to specific members (for manual "Ask Room Info" flow).
     */
    public void requestRoomInfoFromMembers(String entityId, List<String> targetFids) {
        if (entityId == null || targetFids == null || targetFids.isEmpty()) return;
        if (fapiClient == null) return;

        executor.execute(() -> {
            int sent = 0;
            List<String> unreachable = new ArrayList<>();

            for (String targetFid : targetFids) {
                if (targetFid.equals(liveFid)) continue;

                if (!hasReachableChannels(targetFid)) {
                    TimberLogger.w(TAG, "Member %s has no reachable channels, skipping room info request", targetFid);
                    unreachable.add(targetFid);
                    continue;
                }

                String nonce = generateSymkeyNonce();
                ImMessage request = ImMessage.createRequest(
                        ImType.P2P, liveFid, targetFid,
                        RequestType.ROOM_INFO,
                        entityId + ":latest"
                );
                request.setRequestId(nonce);

                MessageQueue.SendResult result = p2pHandler.send(request);
                if (result == MessageQueue.SendResult.SUCCESS) {
                    pendingRoomInfoNonces.add(nonce);
                    TimberLogger.i(TAG, "Sent room info request for %s to member %s (nonce=%s)", entityId, targetFid, nonce);
                    sent++;
                } else {
                    TimberLogger.w(TAG, "Failed to send room info request to %s: %s", targetFid, result);
                }
            }

            if (!unreachable.isEmpty()) {
                String msg = context.getString(R.string.members_no_reachable_channels, unreachable.size());
                mainHandler.post(() -> {
                    for (ImListener l : listeners) l.onError(msg);
                });
            }
        });
    }

    public boolean closeRoom(String roomId) {
        return roomHandler.closeRoom(roomId);
    }

    public boolean leaveRoom(String roomId) {
        return roomHandler.leaveRoom(roomId);
    }

    public boolean isPeerOnline(String fid) {
        return p2pHandler.isPeerOnline(fid);
    }
    
    public Boolean isPeerOnlineOrNull(String fid) {
        return p2pHandler.isPeerOnlineOrNull(fid);
    }
    
    private static final long PING_TIMEOUT_MS = 5000;
    
    public void pingPeer(String fid) {
        if (fudpNode == null || fid == null) return;
        executor.execute(() -> {
            try {
                fudpNode.pingAwaitPong(fid, false, PING_TIMEOUT_MS)
                        .whenComplete((pong, ex) -> {
                            if (ex != null) {
                                TimberLogger.d(TAG, "Ping timeout/failed for %s: %s", fid, ex.getMessage());
                                notifyPresenceChanged(fid, false);
                            }
                        });
                TimberLogger.d(TAG, "Ping sent to %s for presence check", fid);
            } catch (Exception e) {
                TimberLogger.d(TAG, "Ping failed for %s: %s", fid, e.getMessage());
                notifyPresenceChanged(fid, false);
            }
        });
    }
    
    /**
     * Trigger an immediate fetch of new messages.
     * Uses the auto-fetch scheduler to fetch from all registered DOCKs.
     * For backward compat, also supports fetching for specific type/target.
     */
    public void fetchNewMessages(ImType type, String targetId, Long since) {
        if (dockScheduler != null && dockScheduler.isRunning()) {
            dockScheduler.fetchNow();
        } else {
            executor.execute(() -> {
                List<ImMessage> messages = switch (type) {
                    case SQUARE -> squareHandler.fetchSquareMessages(targetId, since);
                    case TEAM -> teamHandler.fetchTeamMessages(targetId, since);
                    case ROOM -> roomHandler.fetchRoomMessages(targetId, since);
                    default -> null;
                };
                
                if (messages != null) {
                    for (ImMessage msg : messages) {
                        messagesDb.put(msg.getId(), msg);
                        updateConversation(msg);
                    }
                }
            });
        }
    }
    
    // ========== Talk Partner Methods ==========
    
    public void addTalkPartner(TalkPartner partner) {
        if (partner == null || partner.getFid() == null) return;
        talkPartnersDb.put(partner.getFid(), partner);
        contactPolicy.addToWhitelist(partner.getFid());
        if (dockRegistry != null && fapiClient != null) {
            dockRegistry.registerTalkPartner(partner, fapiClient);
        }
    }
    
    public TalkPartner getTalkPartner(String fid) {
        if (fid == null) return null;
        return talkPartnersDb.get(fid);
    }
    
    public List<TalkPartner> getAllTalkPartners() {
        List<TalkPartner> partners = new ArrayList<>(talkPartnersDb.getAll().values());
        Collections.sort(partners, (a, b) -> {
            Long aTime = a.getLastTalkedAt() != null ? a.getLastTalkedAt() : a.getAddedAt() != null ? a.getAddedAt() : 0L;
            Long bTime = b.getLastTalkedAt() != null ? b.getLastTalkedAt() : b.getAddedAt() != null ? b.getAddedAt() : 0L;
            return bTime.compareTo(aTime);
        });
        return partners;
    }
    
    public void removeTalkPartner(String fid) {
        if (fid == null) return;
        talkPartnersDb.remove(fid);
        if (dockRegistry != null) {
            dockRegistry.unregister("p2p", fid);
        }
    }
    
    public void updateTalkPartnerHome(String fid, Map<String, String> home) {
        if (fid == null) return;
        TalkPartner partner = talkPartnersDb.get(fid);
        if (partner != null) {
            partner.setHome(home);
            talkPartnersDb.put(partner.getFid(), partner);
        }
    }
    
    public FudpNode getFudpNode() { return fudpNode; }
    public FapiClient getFapiClient() { return fapiClient; }
    public DockServiceRegistry getDockRegistry() { return dockRegistry; }
    public DockFetchScheduler getDockScheduler() { return dockScheduler; }

    /**
     * Retry failed DOCKs for a given entity type (team, square, room).
     * Called from group list activities when the user opens them.
     * Runs on background thread. Callback is invoked on the calling thread.
     *
     * @param entityType "team", "square", or "room"
     * @param callback   optional callback with reconnected DOCK URLs
     */
    public void retryFailedDocks(String entityType, RetryCallback callback) {
        if (dockRegistry == null) return;
        executor.execute(() -> {
            Set<String> reconnected = dockRegistry.retryFailedDocksForType(entityType);
            if (!reconnected.isEmpty() && dockScheduler != null) {
                // Trigger immediate fetch for reconnected DOCKs
                dockScheduler.fetchNow();
            }
            if (callback != null) {
                callback.onRetryComplete(reconnected);
            }
        });
    }

    /**
     * Check if any DOCK serving the given entity type is failed.
     */
    public boolean hasFailedDocks(String entityType) {
        if (dockRegistry == null) return false;
        for (List<DockServiceRegistry.DockEntry> entries : dockRegistry.getEntriesByDockUrl().values()) {
            for (DockServiceRegistry.DockEntry entry : entries) {
                if (entry.entityType.equals(entityType) && dockRegistry.isFailed(entry.dockUrl)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get entity IDs (target IDs) whose DOCK service is unavailable for the given type.
     */
    public Set<String> getUnavailableTargetIds(String entityType) {
        Set<String> result = new java.util.HashSet<>();
        if (dockRegistry == null) return result;
        for (List<DockServiceRegistry.DockEntry> entries : dockRegistry.getEntriesByDockUrl().values()) {
            for (DockServiceRegistry.DockEntry entry : entries) {
                if (entry.entityType.equals(entityType) && dockRegistry.isFailed(entry.dockUrl)) {
                    result.add(entry.entityId);
                }
            }
        }
        return result;
    }

    public interface RetryCallback {
        void onRetryComplete(Set<String> reconnectedDockUrls);
    }
    public DockItemRouter getDockRouter() { return dockRouter; }
    
    /**
     * Called when the app returns to the foreground. After a long sleep the FUDP
     * connections to DOCK servers may have died, leaving those docks marked failed.
     * Reset the per-dock retry cooldowns and trigger an immediate fetch so they
     * reconnect right away instead of waiting out the cooldown + next scheduled cycle.
     */
    public void onAppForeground() {
        if (dockRegistry == null || dockScheduler == null) return;
        if (!dockScheduler.isRunning()) return;
        if (dockRegistry.getFailedDocks().isEmpty()) return;
        TimberLogger.i(TAG, "App foregrounded with %d failed DOCK(s), forcing reconnect",
                dockRegistry.getFailedDocks().size());
        dockRegistry.resetRetryCooldowns();
        dockScheduler.fetchNow();
    }

    /**
     * Set the fetch layer for the dock scheduler.
     * Call ACTIVE when user is in a chat, NORMAL when app is open, BACKGROUND when backgrounded.
     */
    public void setDockFetchLayer(DockFetchScheduler.FetchLayer layer) {
        if (dockScheduler != null) {
            dockScheduler.setLayer(layer);
        }
    }

    /**
     * Mark the dock(s) serving a specific conversation as priority (3s fetch interval).
     * Call when the user enters a chat that uses DOCK delivery.
     */
    public void setActiveChatDock(ImType imType, String targetId) {
        if (dockRegistry == null || dockScheduler == null) return;

        if (imType == ImType.P2P) {
            // P2P messages arrive only in the user's own DOCK; the partner's
            // DOCK is used for sending (direct put), never for fetching.
            setDockPriorityIfRegistered("p2p_self", liveFid);
        } else {
            String entityType = switch (imType) {
                case SQUARE -> SQUARE;
                case TEAM -> TEAM;
                case ROOM -> ROOM;
                default -> null;
            };
            if (entityType != null) {
                setDockPriorityIfRegistered(entityType, targetId);
            }
        }
    }

    private void setDockPriorityIfRegistered(String entityType, String entityId) {
        String dockUrl = dockRegistry.getDockUrl(entityType, entityId);
        if (dockUrl != null) {
            dockScheduler.setPriorityDock(dockUrl);
        }
    }

    /**
     * Clear all priority docks. Call when the user leaves the chat.
     */
    public void clearActiveChatDock() {
        if (dockScheduler != null) {
            dockScheduler.clearPriorityDock();
        }
    }
    
    /**
     * Refresh DOCK registrations (e.g. after adding/removing a talk partner, room, team, or group).
     */
    public void refreshDockRegistry() {
        if (dockRegistry == null || fapiClient == null) return;
        executor.execute(() -> {
            dockRegistry.refreshAll(
                    getAllTalkPartners(),
                    roomHandler.getAllRooms(),
                    teamHandler.getAllTeams(),
                    squareHandler.getAllSquares(),
                    fapiClient
            );
            TimberLogger.d(TAG, "DOCK registry refreshed: %d entries", dockRegistry.size());
        });
    }
    
    public void deleteConversation(String conversationId) {
        if (conversationId == null) return;
        conversationsDb.remove(conversationId);
    }

    public void deleteConversationWithMessages(ImType type, String targetId) {
        if (type == null || targetId == null) return;

        String convId = type.name() + "_" + targetId;
        conversationsDb.remove(convId);

        String convKey = conversationIndexKey(type, targetId);
        List<String> msgIds = messagesDb.getAllFromList(convKey);
        if (msgIds != null) {
            for (String msgId : msgIds) {
                messagesDb.remove(msgId);
            }
        }
        if (messagesDb.getListNames().contains(convKey)) {
            messagesDb.clearList(convKey);
        }
    }

    // ========== Conversation Index ==========
    
    private String conversationIndexKey(ImType type, String targetId) {
        return "idx_" + type.name() + "_" + targetId;
    }
    
    private void addToConversationIndex(ImMessage message) {
        if (message == null || message.getId() == null) return;
        
        String resolvedTarget;
        if (message.getType() == ImType.P2P) {
            resolvedTarget = message.getConversationPartnerId(liveFid);
        } else {
            resolvedTarget = message.getTargetId();
        }
        
        String key = conversationIndexKey(message.getType(), resolvedTarget);
        
        if (!messagesDb.getListNames().contains(key)) {
            messagesDb.createList(key, String.class);
        }
        
        List<String> existing = messagesDb.getAllFromList(key);
        if (existing == null || !existing.contains(message.getId())) {
            messagesDb.addToList(key, message.getId());
        }
    }
    
    // ========== System Messages ==========

    private void addSystemMessage(ImType type, String targetId, String text) {
        ImMessage sysMsg = new ImMessage();
        sysMsg.setId(UUID.randomUUID().toString());
        sysMsg.setType(type);
        sysMsg.setSenderId(null);
        sysMsg.setTargetId(targetId);
        sysMsg.setContentType(ContentType.TEXT);
        sysMsg.setContent(text);
        sysMsg.setTimestamp(System.currentTimeMillis());
        sysMsg.setStatus(MessageStatus.DELIVERED);

        messagesDb.put(sysMsg.getId(), sysMsg);
        addToConversationIndex(sysMsg);
        updateConversation(sysMsg);

        mainHandler.post(() -> {
            for (ImListener l : listeners) l.onMessageReceived(sysMsg);
        });
    }

    // ========== History Request / Response ==========

    /**
     * Handle incoming HISTORY-related messages (requests and responses).
     * @return true if the message was handled and should not be processed further
     */
    private boolean handleHistoryMessage(ImMessage message) {
        if (message == null) return false;

        ContentType ct = message.getContentType();

        if (ct == ContentType.REQUEST && message.getRequestType() == RequestType.HISTORY) {
            TimberLogger.i(TAG, "Received history request from %s", message.getSenderId());
            handleHistoryRequest(message);
            return true;
        }

        if (ct == ContentType.HISTORY) {
            TimberLogger.i(TAG, "Received history response from %s", message.getSenderId());
            handleHistoryResponse(message);
            return true;
        }

        return false;
    }

    /**
     * Handle an incoming history request. Verifies membership and notifies the UI for approval.
     */
    private void handleHistoryRequest(ImMessage requestMsg) {
        String requesterFid = requestMsg.getSenderId();
        String content = requestMsg.getContent();
        if (requesterFid == null || content == null) return;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> req = JsonUtils.fromJson(content, HashMap.class);
            if (req == null) return;

            String imTypeStr = (String) req.get("imType");
            String targetId = (String) req.get("targetId");
            if (imTypeStr == null || targetId == null) return;

            Number sinceNum = (Number) req.get("since");
            Number beforeNum = (Number) req.get("before");
            Long since = sinceNum != null ? sinceNum.longValue() : null;
            Long before = beforeNum != null ? beforeNum.longValue() : null;

            ImType imType = ImType.valueOf(imTypeStr);

            boolean isMember = switch (imType) {
                case P2P -> {
                    TalkPartner partner = getTalkPartner(requesterFid);
                    yield partner != null || requesterFid.equals(targetId) || liveFid.equals(targetId);
                }
                case TEAM -> teamHandler.isMember(targetId, requesterFid);
                case SQUARE -> squareHandler.isMember(targetId, requesterFid);
                case ROOM -> {
                    Room room = roomHandler.getRoom(targetId);
                    yield room != null && room.isMember(requesterFid);
                }
            };

            if (!isMember) {
                TimberLogger.w(TAG, "History request denied: %s is not a member of %s %s",
                        requesterFid, imTypeStr, targetId);
                return;
            }

            mainHandler.post(() -> {
                for (ImListener l : listeners) {
                    l.onHistoryRequestReceived(requesterFid, imTypeStr, targetId,
                            since, before, requestMsg);
                }
            });

        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to parse history request: %s", e.getMessage());
        }
    }

    /**
     * Handle an incoming history response. Validates nonce, downloads, decrypts, and imports.
     */
    private void handleHistoryResponse(ImMessage message) {
        String nonce = message.getRequestId();
        if (nonce == null || !pendingHistoryNonces.contains(nonce)) {
            TimberLogger.d(TAG, "Ignoring unsolicited history response (nonce=%s)", nonce);
            return;
        }
        pendingHistoryNonces.remove(nonce);

        String senderFid = message.getSenderId();
        String hatJson = message.getContent();
        if (hatJson == null) return;

        executor.execute(() -> {
            try {
                Hat rawHat = JsonUtils.fromJson(hatJson, Hat.class);
                if (rawHat == null || rawHat.getId() == null) {
                    TimberLogger.w(TAG, "Invalid HAT in history response");
                    return;
                }

                String plainKey = rawHat.getKey();
                if (plainKey == null || plainKey.isEmpty()) {
                    TimberLogger.w(TAG, "No plain key in history HAT");
                    return;
                }

                HatManager hatManager = HatManager.getInstance(context, liveFid);
                hatManager.addHat(rawHat);
                hatManager.commit();

                DataSyncManager dsm = new DataSyncManager(context, hatManager);
                File outputFile = File.createTempFile("hist_", ".jsonl", context.getCacheDir());

                Setting setting = SettingManager.getInstance().getCurrentSetting();
                byte[] prikey = setting != null ? setting.decryptPrikey() : null;

                boolean downloaded = dsm.downloadData(rawHat, prikey, outputFile);
                if (!downloaded) {
                    TimberLogger.w(TAG, "Failed to download history file: %s", dsm.getLastError());
                    outputFile.delete();
                    return;
                }

                String jsonlContent = new String(Files.readAllBytes(outputFile.toPath()));
                outputFile.delete();

                int count = importConversation(jsonlContent);

                ExportMeta meta = parseExportMeta(jsonlContent);
                ImType imType = meta != null && meta.getImType() != null
                        ? ImType.valueOf(meta.getImType()) : ImType.P2P;
                String targetId = meta != null ? meta.getTargetId() : senderFid;

                String sysText = context.getString(R.string.history_imported, count, senderFid);
                addSystemMessage(imType, targetId != null ? targetId : senderFid, sysText);

                mainHandler.post(() -> {
                    for (ImListener l : listeners) l.onHistoryImported(senderFid, count);
                });

                TimberLogger.i(TAG, "Imported %d messages from history shared by %s", count, senderFid);

            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to process history response: %s", e.getMessage());
            }
        });
    }

    private ExportMeta parseExportMeta(String jsonlContent) {
        if (jsonlContent == null) return null;
        int newlineIdx = jsonlContent.indexOf('\n');
        String firstLine = newlineIdx > 0 ? jsonlContent.substring(0, newlineIdx).trim() : jsonlContent.trim();
        try {
            ExportMeta meta = ExportMeta.fromJson(firstLine);
            if (meta != null && meta.getVersion() != null && meta.getEntityType() != null) {
                return meta;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Send a history request to a specific member.
     */
    public void requestHistory(ImType imType, String targetId, String targetFid,
                                Long sinceTs, Long beforeTs) {
        executor.execute(() -> {
            if (!hasReachableChannels(targetFid)) {
                String msg = context.getString(R.string.owner_no_reachable_channels);
                mainHandler.post(() -> {
                    for (ImListener l : listeners) l.onError(msg);
                });
                return;
            }

            Map<String, Object> reqMap = new HashMap<>();
            reqMap.put("imType", imType.name());
            reqMap.put("targetId", targetId);
            reqMap.put("since", sinceTs != null ? sinceTs : 0L);
            reqMap.put("before", beforeTs != null ? beforeTs : System.currentTimeMillis());

            String content = JsonUtils.toJson(reqMap);
            String nonce = generateSymkeyNonce();

            ImMessage request = ImMessage.createRequest(
                    ImType.P2P, liveFid, targetFid,
                    RequestType.HISTORY, content
            );
            request.setRequestId(nonce);

            MessageQueue.SendResult result = p2pHandler.send(request);
            if (result == MessageQueue.SendResult.SUCCESS) {
                pendingHistoryNonces.add(nonce);
                String sysText = context.getString(R.string.history_request_sent, targetFid);
                addSystemMessage(imType, targetId, sysText);
                TimberLogger.i(TAG, "Sent history request for %s %s to %s (nonce=%s)",
                        imType.name(), targetId, targetFid, nonce);
            } else {
                TimberLogger.w(TAG, "Failed to send history request to %s: %s", targetFid, result);
            }
        });
    }

    /**
     * Approve a history request: export messages, upload to DISK, and send response.
     */
    public void approveHistoryRequest(ImMessage requestMsg) {
        if (requestMsg == null) return;

        executor.execute(() -> {
            String requesterFid = requestMsg.getSenderId();
            String content = requestMsg.getContent();
            if (requesterFid == null || content == null) return;

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = JsonUtils.fromJson(content, HashMap.class);
                if (req == null) return;

                String imTypeStr = (String) req.get("imType");
                String targetId = (String) req.get("targetId");
                if (imTypeStr == null || targetId == null) return;

                Number sinceNum = (Number) req.get("since");
                Number beforeNum = (Number) req.get("before");
                Long sinceTs = sinceNum != null ? sinceNum.longValue() : null;
                Long beforeTs = beforeNum != null ? beforeNum.longValue() : null;

                ImType imType = ImType.valueOf(imTypeStr);

                File exportFile = exportConversationToFile(imType, targetId, sinceTs, beforeTs,
                        requesterFid, context.getCacheDir());

                if (exportFile == null || !exportFile.exists() || exportFile.length() == 0) {
                    TimberLogger.w(TAG, "Export file is empty or null");
                    return;
                }

                HatManager hatManager = HatManager.getInstance(context, liveFid);

                byte[] hashBytes = Hash.sha256x2Bytes(exportFile);
                String did = Hex.toHex(hashBytes);

                Hat rawHat = new Hat();
                rawHat.setId(did);
                rawHat.setName(buildExportFilename(imType, targetId, sinceTs, beforeTs));
                rawHat.setSize(exportFile.length());
                rawHat.setBorn(System.currentTimeMillis());
                rawHat.setLast(System.currentTimeMillis());

                hatManager.addHat(rawHat);
                hatManager.commit();

                DataSyncManager dsm = new DataSyncManager(context, hatManager);
                com.fc.fc_ajdk.data.fcData.DiskItem diskItem = dsm.uploadData(exportFile, rawHat, false);

                if (diskItem == null) {
                    TimberLogger.w(TAG, "Failed to upload history to DISK: %s", dsm.getLastError());
                    exportFile.delete();
                    return;
                }

                byte[] symkey = dsm.getLastSymkey();
                rawHat.setKey(Hex.toHex(symkey));

                ImMessage response = new ImMessage();
                response.setType(ImType.P2P);
                response.setSenderId(liveFid);
                response.setTargetId(requesterFid);
                response.setContentType(ContentType.HISTORY);
                response.setContent(rawHat.toJson());
                if (requestMsg.getRequestId() != null) {
                    response.setRequestId(requestMsg.getRequestId());
                }
                response.setTimestamp(System.currentTimeMillis());

                send(response);

                int msgCount = countExportedMessages(exportFile);
                String sysText = context.getString(R.string.history_shared, msgCount, requesterFid);
                addSystemMessage(imType, targetId, sysText);

                exportFile.delete();

                TimberLogger.i(TAG, "Approved history request: shared %d messages to %s",
                        msgCount, requesterFid);

            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to approve history request: %s", e.getMessage());
            }
        });
    }

    private int countExportedMessages(File file) {
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            return Math.max(0, lines.size() - 1);
        } catch (Exception e) {
            return 0;
        }
    }

    // ========== Conversation Export / Import ==========

    /**
     * Export conversation messages to a JSONL file. Each line is one ImMessage JSON.
     * For team/room messages, plain text content is exported and cipher is cleared.
     */
    public File exportConversationToFile(ImType type, String targetId, Long sinceTs, Long beforeTs,
                                          String sharedToFid, File outputDir) {
        List<ImMessage> messages = getMessages(type, targetId, 0, sinceTs, beforeTs);
        Collections.sort(messages, (a, b) -> {
            Long aTime = a.getTimestamp() != null ? a.getTimestamp() : 0L;
            Long bTime = b.getTimestamp() != null ? b.getTimestamp() : 0L;
            return aTime.compareTo(bTime);
        });

        String filename = buildExportFilename(type, targetId, sinceTs, beforeTs);
        File outputFile = new File(outputDir, filename);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            ExportMeta meta = ExportMeta.createHistory(liveFid, type.name(), targetId,
                    sinceTs, beforeTs, sharedToFid);
            writer.write(meta.toJson());
            writer.newLine();

            for (ImMessage msg : messages) {
                if (msg.getContent() != null) {
                    msg.setCipher(null);
                }
                writer.write(msg.toJson());
                writer.newLine();
            }
            return outputFile;
        } catch (IOException e) {
            TimberLogger.e(TAG, "Failed to export conversation to file: %s", e.getMessage());
            return null;
        }
    }

    public String buildExportFilename(ImType type, String targetId, Long sinceTs, Long beforeTs) {
        StringBuilder sb = new StringBuilder();
        sb.append(type.name().toLowerCase());
        sb.append('_');
        
        if (type == ImType.P2P) {
            String a = liveFid;
            String b = targetId;
            if (a.compareTo(b) > 0) { String tmp = a; a = b; b = tmp; }
            sb.append(a).append('_').append(b);
        } else {
            sb.append(targetId);
        }
        
        sb.append('_');
        sb.append(sinceTs != null ? sinceTs : 0);
        sb.append('_');
        sb.append(beforeTs != null ? beforeTs : System.currentTimeMillis());
        sb.append(".imhist");
        
        return sb.toString();
    }
    
    /**
     * Import conversation messages from a JSONL string.
     * First line may be an ExportMeta (skipped). Each subsequent line is an ImMessage JSON.
     * Duplicate messages (by ID) are skipped. Imported messages are marked as IMPORTED.
     */
    public int importConversation(String historyJsonl) {
        if (historyJsonl == null || historyJsonl.isEmpty()) return 0;
        
        try {
            String[] lines = historyJsonl.split("\n");
            int imported = 0;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                ExportMeta meta = null;
                try {
                    meta = ExportMeta.fromJson(trimmed);
                } catch (Exception ignored) {}
                if (meta != null && meta.getVersion() != null && meta.getEntityType() != null) {
                    continue;
                }

                ImMessage msg = ImMessage.fromJson(trimmed);
                if (msg == null || msg.getId() == null) continue;
                if (messagesDb.get(msg.getId()) != null) continue;

                msg.setStatus(MessageStatus.IMPORTED);
                messagesDb.put(msg.getId(), msg);
                addToConversationIndex(msg);
                updateConversation(msg);
                imported++;
            }
            return imported;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to import conversation: %s", e.getMessage());
            return 0;
        }
    }
}
