package com.fc.freer.call;

/**
 * The messages between the main process and the {@code :voice} process
 * (VOICE_SPEC §11.3), over a {@link android.os.Messenger}: commands down,
 * events up. The main process keeps the identity, the signalling and the
 * symkeys; it hands the voice process a call's transport key, delegations and
 * call secrets, and nothing more.
 */
final class VoiceProtocol {

    private VoiceProtocol() {}

    // ===== Commands, main → voice =====

    /** {@code replyTo} is where events go. */
    static final int REGISTER = 1;
    /** Start a 1:1 call: the call's fields, and for the callee, its secret. */
    static final int CALL_START = 2;
    /** Caller: answered; the secret and the callee's delegation. */
    static final int CALL_ANSWERED = 3;
    static final int CALL_END = 4;
    static final int SET_MUTED = 5;
    static final int SET_SPEAKER = 6;
    /** Join or start a meeting: the meeting, the join's key and delegation, and the secrets at hand. */
    static final int MEETING_JOIN = 7;
    static final int MEETING_LEAVE = 8;
    static final int MEETING_HAND = 9;
    static final int MEETING_CONTROL = 10;
    /** The answer to {@link #EVT_SECRET_REQUEST}: a secret, or none. */
    static final int MEETING_SECRET = 11;
    /** The answer to {@link #EVT_REKEY_CHECK}, when there is a rotation to follow. */
    static final int MEETING_REKEY = 12;

    // ===== Events, voice → main =====

    static final int EVT_CALL_STATE = 101;
    /** Caller: the call is open on the relay; send the INVITE. */
    static final int EVT_CALL_RING = 102;
    static final int EVT_CALL_KNOCK = 103;
    static final int EVT_CALL_PEER_LEFT = 104;
    static final int EVT_CALL_UNVERIFIED = 105;
    /** Once a second while a call runs: its path, RTT and mute. */
    static final int EVT_CALL_STATUS = 106;
    static final int EVT_CALL_PEER_SILENT = 107;
    static final int EVT_MEETING_JOINED = 110;
    /** A {@link MeetingView} of the running meeting. */
    static final int EVT_MEETING_VIEW = 111;
    static final int EVT_MEETING_REKEYED = 112;
    static final int EVT_MEETING_ENDED = 113;
    static final int EVT_SECRET_REQUEST = 114;
    static final int EVT_REKEY_CHECK = 115;
    static final int EVT_CONTROL_RESULT = 116;
    /** The ongoing-call notification's hang-up button. */
    static final int EVT_HANGUP_REQUEST = 120;

    // ===== Bundle keys =====

    static final String CALL_ID = "callId";
    static final String PEER_FID = "peerFid";
    static final String MY_FID = "myFid";
    static final String RELAY_URL = "relayUrl";
    static final String RELAY_PUBKEY = "relayPubkey";
    static final String RELAY_SID = "relaySid";
    static final String T_PRIV = "tPriv";
    static final String MY_DELEGATION = "myDelegation";
    static final String PEER_DELEGATION = "peerDelegation";
    static final String SECRET = "secret";
    static final String OUTGOING = "outgoing";
    static final String ALLOW_DIRECT = "allowDirect";
    static final String ON = "on";
    static final String STATE = "state";
    static final String DETAIL = "detail";
    static final String PAYMENT_REQUIRED = "paymentRequired";
    static final String NETWORK_BLOCKED = "networkBlocked";
    static final String FID = "fid";
    static final String DIRECT = "direct";
    static final String RTT_MS = "rttMs";
    static final String MUTED = "muted";
    static final String MEETING = "meeting";
    static final String MEETING_ID = "meetingId";
    static final String CREATING = "creating";
    static final String SECRETS = "secrets";
    static final String SYMKEY_VERSION = "symkeyVersion";
    static final String NONCE = "nonce";
    static final String AUTH_PUB = "authPub";
    static final String END_FOR_ALL = "endForAll";
    static final String REQUEST_ID = "requestId";
    static final String ACTION = "action";
    static final String TARGET_FID = "targetFid";
    static final String TARGET_SSRC = "targetSsrc";
    static final String ERROR = "error";
    static final String VIEW = "view";
    static final String SIGNAL = "signal";
    static final String WHY = "why";
    /** Cancel echo with the app's own canceller rather than the phone's (§11.1). */
    static final String OWN_AEC = "ownAec";

    /** The key a secret is cached under: one key set of a meeting. */
    static String keySet(long symkeyVersion, String nonceHex, String authPubHex) {
        return symkeyVersion + "|" + nonceHex + "|" + authPubHex;
    }
}
