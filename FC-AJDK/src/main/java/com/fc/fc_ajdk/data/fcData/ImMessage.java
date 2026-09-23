package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.utils.JsonUtils;
import com.google.gson.annotations.JsonAdapter;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.SchnorrSignature;

import org.bitcoinj.core.ECKey;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Instant Message entity for all communication types (p2p, group, team, room).
 *
 * ID: assigned externally by the FUDP layer as a globally unique 8-byte long,
 * stored as a 16-char lowercase hex string for MMKV / LocalDB compatibility.
 *
 * Serialization:
 *   - toJson() / fromJson(): full object for local storage (MMKVDB)
 *   - toWireBytes() / fromWireBytes(): compact binary for network transmission
 */
public class ImMessage extends FcEntity {
    
    // Type and routing
    private ImType type;           // P2P, GROUP, TEAM, ROOM
    private String senderId;       // FID of sender
    private String targetId;       // FID (p2p), groupId, teamId, or roomId
    
    // Timing and ordering
    private Long timestamp;        // Milliseconds since epoch
    private Long sequence;         // For ordering within same millisecond
    
    // Content
    private ContentType contentType; // TEXT, HAT, STREAM, SYMKEY, MEMBERS, HISTORY, etc.
    /**
     * Text, HAT JSON, or request/response data. On the wire this is the FIRST
     * SECTION OF THE BODY, not a field of its own -- see toWireBytes().
     */
    private String content;
    /**
     * Inline binary payload -- audio, an attachment, a wrapped key.
     *
     * <p>Raw bytes, not Base64. v1 carried this as a Base64 string because the
     * wire had no way to express bytes; v2's body framing does, so the +33% is
     * gone. Local JSON still writes it Base64 (FIMP0V2 s7) via the adapter.
     *
     * <p>Whether a payload may travel inline at all is a property of the
     * DESTINATION, not a constant -- see ASSUMED_DOCK_ITEM_LIMIT.
     */
    @JsonAdapter(Base64BytesAdapter.class)
    private byte[] data;
    
    // For REQUEST/RESPONSE types
    private RequestType requestType;
    private String requestId;      // ID of the request being responded to
    
    // Delivery tracking (local-only, never sent on wire)
    private List<String> roadIds;  // ROAD servers that relayed in sequence
    private String dockId;         // If stored in DOCK
    private DeliveryMethod deliveryMethod;
    private MessageStatus status;
    private Long deliveredAt;
    private Long readAt;
    
    // Encryption (for team/room/p2p)
    /**
     * The sealed body -- a binary CryptoDataByte bundle (see toBundle()).
     *
     * <p>Replaces v1's `cipher`, and the replacement is the point of the
     * version break: v1 sealed `content` and left `dataBase64` beside it in the
     * clear, so a voice note travelled with its metadata encrypted and its
     * audio readable by the DOCK operator. Here the seal covers content AND
     * data together, so that state cannot be built.
     */
    @JsonAdapter(Base64BytesAdapter.class)
    private byte[] body;
    private Long symkeyVersion;    // Which version of symkey sealed `body`
    
    // Threading
    private String replyToId;      // For replies/threading
    private String threadId;       // Group related messages
    
    // Sender info cache (local-only, for display)
    private String senderName;     // CID or label of sender
    
    // Flags (local-only)
    private Boolean unread;
    private Boolean pinned;
    private Boolean deleted;       // Soft delete
    
    // ========== Size threshold ==========

    /**
     * What to assume a DOCK will accept per item when its service record does
     * not say -- FAPI13's own DEFAULT_MAX_DATA_SIZE.
     *
     * <p>This is a FLOOR TO FALL BACK ON, NOT A LIMIT TO ENFORCE. The real
     * ceiling is whatever the destination DOCK advertises as `maxDataSize` in
     * its on-chain service record, and it varies by operator. Resolve it per
     * destination and measure the ENCODED ENVELOPE against that.
     *
     * <p>v1 had a fixed MAX_INLINE_DATA_SIZE of 900 KB here, justified by an
     * assumed 1 MB server limit that does not exist. The server's default is
     * this value -- 14x smaller -- so inline binary failed long before the
     * documented limit, and the failure looked like a wire bug.
     */
    public static final int ASSUMED_DOCK_ITEM_LIMIT = 64 * 1024;

    // ========== Wire format constants ==========

    /**
     * FIMP magic, then the wire version -- the first two bytes of every v2
     * envelope.
     *
     * <p>The magic byte is load-bearing, not decoration. A v1 envelope opens
     * with the ImType ordinal, a value in 0..3, so a bare version byte of 2 is
     * indistinguishable from a v1 TEAM message: a v2 reader would parse legacy
     * team traffic as v2 and misparse it silently instead of rejecting it.
     * 0xF1 cannot occur as a v1 first byte, so rejection is deterministic in
     * both directions.
     */
    public static final byte WIRE_MAGIC = (byte) 0xF1;
    public static final byte WIRE_VERSION = (byte) 0x03;

    /**
     * FIMP0V3: every envelope ends with the author's 33-byte compressed pubkey
     * and a 64-byte Schnorr signature. Mandatory, so it has no flag.
     */
    public static final int SIGNATURE_TRAILER_SIZE = 33 + 64;

    /** Prefixed to the signed bytes, so a message signature is never valid for anything else. */
    private static final byte[] SIGNATURE_TAG = "FIMP-SIG".getBytes(StandardCharsets.UTF_8);

    /** Shortest legal encoding: magic, version, header, empty ids, no flags. */
    private static final int WIRE_HEADER_SIZE = 16;

    // 2-byte flags for optional wire fields
    private static final int FLAG_BODY            = 0x0001;
    private static final int FLAG_BODY_SEALED     = 0x0002;
    private static final int FLAG_SYMKEY_VERSION  = 0x0004;
    private static final int FLAG_REQUEST_TYPE    = 0x0008;
    private static final int FLAG_REQUEST_ID      = 0x0010;
    private static final int FLAG_REPLY_TO_ID     = 0x0020;
    private static final int FLAG_THREAD_ID       = 0x0040;
    private static final int FLAG_MESSAGE_ID      = 0x0080;
    // bits 8-15 reserved
    
    // ========== ID helpers ==========

    /**
     * Convert a FUDP long message ID to a 16-char hex string for use as entity ID.
     */
    public static String longIdToHex(long id) {
        return String.format("%016x", id);
    }

    /**
     * Convert a 16-char hex string back to a long.
     */
    public static long hexIdToLong(String hexId) {
        return Long.parseUnsignedLong(hexId, 16);
    }

    /**
     * Set the message ID from a FUDP long.
     */
    public void setIdFromLong(long fudpId) {
        this.id = longIdToHex(fudpId);
    }

    /**
     * Override FcEntity.getId() to NOT auto-generate a hash-based ID.
     * ImMessage IDs are assigned externally by the FUDP layer as 16-char hex strings.
     * Auto-generation via makeId() would produce a 64-char hash that is incompatible
     * with hexIdToLong() and would silently break FUDP CHAT delivery.
     */
    @Override
    public String getId() {
        return this.id;
    }

    /**
     * Check whether a FUDP-compatible 16-char hex ID has been assigned.
     */
    public boolean hasFudpId() {
        return this.id != null && this.id.length() == 16;
    }

    // ========== Factory methods ==========
    
    private static ImMessage createBase(ImType type, String senderId, String targetId, ContentType contentType) {
        ImMessage msg = new ImMessage();
        msg.setType(type);
        msg.setSenderId(senderId);
        msg.setTargetId(targetId);
        msg.setContentType(contentType);
        msg.setTimestamp(System.currentTimeMillis());
        msg.setStatus(MessageStatus.PENDING);
        return msg;
    }

    /**
     * Create a text message. ID is assigned later by the FUDP layer or ImManager.
     */
    public static ImMessage createText(ImType type, String senderId, String targetId, String text) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.TEXT);
        msg.setContent(text);
        msg.setUnread(false);
        return msg;
    }
    
    /**
     * Create a CALL message (VOICE_SPEC §3): {@code json} is a call signal, or,
     * for a local call record that is never sent, the record.
     */
    public static ImMessage createCall(ImType type, String senderId, String targetId, String json) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.CALL);
        msg.setContent(json);
        msg.setUnread(false);
        return msg;
    }

    /**
     * Create a STREAM message for inline binary data sharing.
     * Content holds metadata JSON (name, size, type); data holds the raw payload.
     */
    public static ImMessage createStream(ImType type, String senderId, String targetId,
                                          String metaJson, byte[] data) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.STREAM);
        msg.setContent(metaJson);
        msg.setData(data);
        msg.setUnread(false);
        return msg;
    }

    /**
     * Create a HAT reference message.
     */
    public static ImMessage createHat(ImType type, String senderId, String targetId, Hat hat) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.HAT);
        msg.setContent(hat.toJson());
        msg.setUnread(false);
        return msg;
    }
    
    /**
     * Create a request message.
     */
    public static ImMessage createRequest(ImType type, String senderId, String targetId,
                                          RequestType requestType, String requestData) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.REQUEST);
        msg.setRequestType(requestType);
        msg.setContent(requestData);
        return msg;
    }
    
    /**
     * Create a response message.
     */
    public static ImMessage createResponse(ImType type, String senderId, String targetId,
                                           String requestId, String responseData) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.RESPONSE);
        msg.setRequestId(requestId);
        msg.setContent(responseData);
        return msg;
    }
    
    /**
     * Create a receipt message.
     */
    public static ImMessage createReceipt(String senderId, String targetId,
                                          String originalMessageId, boolean isRead) {
        ImMessage msg = createBase(ImType.P2P, senderId, targetId, ContentType.RECEIPT);
        msg.setRequestId(originalMessageId);
        msg.setContent(isRead ? "read" : "delivered");
        return msg;
    }

    /**
     * Create a symkey push message (proactive sharing of symmetric key).
     */
    public static ImMessage createSymkey(ImType type, String senderId, String targetId,
                                         String symkeyData, long version) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.SYMKEY);
        msg.setContent(symkeyData);
        msg.setSymkeyVersion(version);
        return msg;
    }

    /**
     * Create a members push message (proactive sharing of member list).
     */
    public static ImMessage createMembers(ImType type, String senderId, String targetId,
                                           String membersJson) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.MEMBERS);
        msg.setContent(membersJson);
        return msg;
    }

    /**
     * Create a room info push message (shares all room information: name, desc, members, symkey).
     * Content holds the RoomInfo JSON.
     */
    public static ImMessage createRoomInfo(ImType type, String senderId, String targetId,
                                           String roomInfoJson) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.ROOM_INFO);
        msg.setContent(roomInfoJson);
        return msg;
    }

    /**
     * Create a room leave notification.
     * Content holds the roomId of the room being left.
     */
    public static ImMessage createRoomLeave(ImType type, String senderId, String targetId,
                                             String roomId) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.ROOM_LEAVE);
        msg.setContent(roomId);
        return msg;
    }

    /**
     * Create a room accept notification (invitee confirms joining to the owner).
     * Content holds the roomId of the room being joined.
     */
    public static ImMessage createRoomAccept(ImType type, String senderId, String targetId,
                                              String roomId) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.ROOM_ACCEPT);
        msg.setContent(roomId);
        return msg;
    }

    /**
     * Create a room disband notification (owner informs members the room is closed).
     * Content holds the roomId of the room being disbanded.
     */
    public static ImMessage createRoomDisband(ImType type, String senderId, String targetId,
                                               String roomId) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.ROOM_DISBAND);
        msg.setContent(roomId);
        return msg;
    }

    /**
     * Create a room removed notification (owner informs a member they were removed).
     * Content holds the roomId of the room the member was removed from.
     */
    public static ImMessage createRoomRemoved(ImType type, String senderId, String targetId,
                                               String roomId) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.ROOM_REMOVED);
        msg.setContent(roomId);
        return msg;
    }

    /**
     * Create a voice message for inline audio.
     * Content holds metadata JSON: {"durationMs":..., "sampleRate":..., "format":"aac"}
     * data holds the raw AAC audio payload.
     */
    public static ImMessage createVoice(ImType type, String senderId, String targetId,
                                         String metaJson, byte[] data) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.VOICE);
        msg.setContent(metaJson);
        msg.setData(data);
        msg.setUnread(false);
        return msg;
    }

    /**
     * Create a history push message (proactive sharing of message history).
     * Content holds the HAT reference JSON for the history file; data holds
     * the kCipher (symkey encrypted with receiver's pubkey) if encrypted.
     */
    public static ImMessage createHistory(ImType type, String senderId, String targetId,
                                           String hatJson, byte[] kCipher) {
        ImMessage msg = createBase(type, senderId, targetId, ContentType.HISTORY);
        msg.setContent(hatJson);
        msg.setData(kCipher);
        return msg;
    }
    
    // ========== Utility ==========
    
    /**
     * Check if this is an outgoing message for the given FID.
     */
    public boolean isOutgoing(String myFid) {
        return myFid != null && myFid.equals(senderId);
    }
    
    /**
     * Get the conversation partner FID for p2p messages.
     */
    public String getConversationPartnerId(String myFid) {
        if (type != ImType.P2P) return targetId;
        return myFid.equals(senderId) ? targetId : senderId;
    }
    
    // ========== JSON serialization (for storage) ==========
    
    public String toJson() {
        return JsonUtils.toJson(this);
    }
    
    public String toNiceJson() {
        return JsonUtils.toNiceJson(this);
    }
    
    public static ImMessage fromJson(String json) {
        return JsonUtils.fromJson(json, ImMessage.class);
    }
    
    // ========== Binary wire serialization ==========

    /**
     * Serialize to the FIMP v3 compact binary envelope, signed with the
     * sender's prikey.
     *
     * <pre>
     *   magic(1)=0xF1 version(1)=0x03
     *   type(1) contentType(1)
     *   senderId(lenPfx8) targetId(lenPfx8)
     *   timestamp(8) flags(2)
     *   [body(lenPfx32)]
     *   [symkeyVersion(4)] [requestType(1)]
     *   [requestId] [replyToId] [threadId] [id]   -- each lenPfx16
     *   senderPubkey(33) signature(64)            -- FIMP0V3 trailer
     * </pre>
     *
     * <p><b>Signed, always.</b> The sender field is text anyone can write, and
     * the servers a message passes through cannot vouch for it (a DOCK forward
     * re-puts it under the forwarding server's own identity). So the author
     * signs SHA256(SHA256("FIMP-SIG" || everything before the trailer)) with
     * the key of senderId, and a receiver verifies before reading anything.
     *
     * <p><b>One private field.</b> `content` and `data` are not fields here at
     * all: they are framed together into the body (see bodyFraming()), and it
     * is that framing the mode's cipher seals. v1 had three payload fields and
     * sealed one of them, which is how a voice note shipped with encrypted
     * metadata and cleartext audio. With a single field the rule is "seal the
     * body", and the half-sealed state has no encoding.
     *
     * <p><b>A length that does not fit now throws.</b> v1 wrote every payload
     * length with putShort(), which silently wrapped past 65535 and corrupted
     * every field after it. That is the failure this version exists to end, so
     * an over-long field is an exception, never a truncation.
     *
     * @throws IllegalStateException if a field cannot be length-prefixed
     */
    public byte[] toWireBytes(byte[] prikey) {
        byte[] unsigned = unsignedWireBytes();
        ECKey key = ECKey.fromPrivate(prikey);
        byte[] pubkey = key.getPubKey();
        String signer = KeyTools.pubkeyToFchAddr(pubkey);
        if (senderId == null || !senderId.equals(signer)) {
            // Signing another FID's message would produce an envelope every
            // receiver discards; failing here names the bug at its source.
            throw new IllegalStateException("Cannot sign a message from " + senderId + " with the key of " + signer);
        }
        byte[] signature = SchnorrSignature.schnorr_sign(signatureHash(unsigned, unsigned.length), key.getPrivKey());
        ByteBuffer out = ByteBuffer.allocate(unsigned.length + SIGNATURE_TRAILER_SIZE);
        out.put(unsigned);
        out.put(pubkey);
        out.put(signature);
        return out.array();
    }

    /** SHA256(SHA256("FIMP-SIG" || envelope[0, length))), as FTSP24 hashes a message. */
    private static byte[] signatureHash(byte[] envelope, int length) {
        byte[] input = new byte[SIGNATURE_TAG.length + length];
        System.arraycopy(SIGNATURE_TAG, 0, input, 0, SIGNATURE_TAG.length);
        System.arraycopy(envelope, 0, input, SIGNATURE_TAG.length, length);
        return Hash.sha256x2(input);
    }

    /**
     * The envelope up to, not including, the signature trailer: what the
     * signature covers. Only {@link #toWireBytes(byte[])} puts it on the wire.
     */
    byte[] unsignedWireBytes() {
        // A sealed body is carried as-is; an unsealed one is framed here.
        //
        // EMPTY COUNTS AS ABSENT. The framing records a length, not a presence,
        // so a zero-length section reads back as null and cannot read back as
        // "". Encoding an empty payload as an 8-byte all-zero framing would
        // make the round trip unstable: decode would yield null, and
        // re-encoding would emit no body at all.
        byte[] wireBody;
        boolean sealed = body != null && body.length > 0;
        if (sealed) {
            wireBody = body;
        } else if ((content != null && !content.isEmpty()) || (data != null && data.length > 0)) {
            wireBody = bodyFraming();
        } else {
            wireBody = null;
        }

        byte[] requestIdBytes = null;
        byte[] replyToIdBytes = null;
        byte[] threadIdBytes = null;
        byte[] messageIdBytes = null;

        int flags = 0;
        if (wireBody != null) flags |= FLAG_BODY;
        if (sealed) flags |= FLAG_BODY_SEALED;
        if (symkeyVersion != null) flags |= FLAG_SYMKEY_VERSION;
        if (requestType != null) flags |= FLAG_REQUEST_TYPE;
        if (requestId != null) {
            flags |= FLAG_REQUEST_ID;
            requestIdBytes = requestId.getBytes(StandardCharsets.UTF_8);
        }
        if (replyToId != null) {
            flags |= FLAG_REPLY_TO_ID;
            replyToIdBytes = replyToId.getBytes(StandardCharsets.UTF_8);
        }
        if (threadId != null) {
            flags |= FLAG_THREAD_ID;
            threadIdBytes = threadId.getBytes(StandardCharsets.UTF_8);
        }
        if (id != null) {
            flags |= FLAG_MESSAGE_ID;
            messageIdBytes = id.getBytes(StandardCharsets.UTF_8);
        }

        byte[] senderIdBytes = senderId != null ? senderId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] targetIdBytes = targetId != null ? targetId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        checkLen8(senderIdBytes, "senderId");
        checkLen8(targetIdBytes, "targetId");
        checkLen16(requestIdBytes, "requestId");
        checkLen16(replyToIdBytes, "replyToId");
        checkLen16(threadIdBytes, "threadId");
        checkLen16(messageIdBytes, "id");
        checkSymkeyVersion(symkeyVersion);

        int size = 1 + 1                                       // magic + version
                + 1 + 1                                        // type + contentType
                + 1 + senderIdBytes.length
                + 1 + targetIdBytes.length
                + 8                                            // timestamp
                + 2;                                           // flags

        if (wireBody != null) size += 4 + wireBody.length;
        if (symkeyVersion != null) size += 4;
        if (requestType != null) size += 1;
        if (requestIdBytes != null) size += 2 + requestIdBytes.length;
        if (replyToIdBytes != null) size += 2 + replyToIdBytes.length;
        if (threadIdBytes != null) size += 2 + threadIdBytes.length;
        if (messageIdBytes != null) size += 2 + messageIdBytes.length;

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(WIRE_MAGIC);
        buf.put(WIRE_VERSION);
        buf.put((byte) (type != null ? type.ordinal() : 0));
        buf.put((byte) (contentType != null ? contentType.ordinal() : 0));
        writeLenPfx8(buf, senderIdBytes);
        writeLenPfx8(buf, targetIdBytes);
        buf.putLong(timestamp != null ? timestamp : 0L);
        buf.putShort((short) flags);

        if (wireBody != null) {
            buf.putInt(wireBody.length);
            buf.put(wireBody);
        }
        if (symkeyVersion != null) buf.putInt((int) (symkeyVersion & 0xFFFFFFFFL));
        if (requestType != null) buf.put((byte) requestType.ordinal());
        if (requestIdBytes != null) writeLenPfx16(buf, requestIdBytes);
        if (replyToIdBytes != null) writeLenPfx16(buf, replyToIdBytes);
        if (threadIdBytes != null) writeLenPfx16(buf, threadIdBytes);
        if (messageIdBytes != null) writeLenPfx16(buf, messageIdBytes);

        return buf.array();
    }

    /**
     * Deserialize a FIMP v3 envelope and verify who wrote it. An envelope
     * whose signature is missing, is not the named sender's, or does not
     * verify is rejected like any other malformed input.
     *
     * <p>A sealed body lands in `body` and stays sealed; `content` and `data`
     * are populated only once something opens it. An unsealed body is unframed
     * here and there is nothing left to open.
     *
     * <p>If the wire data includes a message ID (FLAG_MESSAGE_ID) it is
     * restored; otherwise the caller sets it from the FUDP message ID or the
     * ROAD/DOCK header.
     *
     * @throws IllegalArgumentException on anything that is not a signed v3 envelope
     */
    public static ImMessage fromWireBytes(byte[] wire) {
        if (wire == null || wire.length < WIRE_HEADER_SIZE) {
            throw new IllegalArgumentException("Wire data too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire);
        ImMessage msg = new ImMessage();

        byte magic = buf.get();
        byte version = buf.get();
        if (magic != WIRE_MAGIC) {
            // A v1 envelope opens with the ImType ordinal, so a first byte in
            // 0..3 is almost certainly one -- worth saying, because "not a FIMP
            // envelope" would be misleading for the one case that really is.
            if ((magic & 0xFF) <= 3) {
                throw new IllegalArgumentException(
                        "Looks like a FIMP v1 envelope -- v2 does not read v1, and there is no negotiation");
            }
            throw new IllegalArgumentException(
                    String.format("Not a FIMP envelope (first byte 0x%02x, expected 0xf1)", magic & 0xFF));
        }
        if (version != WIRE_VERSION) {
            throw new IllegalArgumentException("Unsupported FIMP wire version " + (version & 0xFF));
        }

        try {
            int typeOrd = buf.get() & 0xFF;
            if (typeOrd < ImType.values().length) msg.setType(ImType.values()[typeOrd]);

            int ctOrd = buf.get() & 0xFF;
            if (ctOrd < ContentType.values().length) msg.setContentType(ContentType.values()[ctOrd]);

            msg.setSenderId(readLenPfx8(buf));
            msg.setTargetId(readLenPfx8(buf));
            msg.setTimestamp(buf.getLong());

            int flags = buf.getShort() & 0xFFFF;

            if ((flags & FLAG_BODY) != 0) {
                int length = buf.getInt();
                if (length < 0 || length > buf.remaining()) {
                    throw new IllegalArgumentException("Body length " + length + " exceeds the envelope");
                }
                byte[] raw = new byte[length];
                buf.get(raw);
                if ((flags & FLAG_BODY_SEALED) != 0) {
                    msg.setBody(raw);
                } else {
                    msg.applyBodyFraming(raw);
                }
            } else if ((flags & FLAG_BODY_SEALED) != 0) {
                throw new IllegalArgumentException("bodySealed flag with no body");
            }
            // Sign-extended, as v1 did: the field is a Long that the wire
            // carries in 32 bits, so a version past 2^31 arrives negative.
            // Unsigned: a version is the second it was minted in
            // (FIMP0V2 Symkey id), and `(long) getInt()` made every key
            // minted after January 2038 arrive negative -- which is not a
            // version, so it would have been rejected. Read this way the
            // field is good until 2106, and no byte on the wire changes.
            if ((flags & FLAG_SYMKEY_VERSION) != 0) {
                msg.setSymkeyVersion(buf.getInt() & 0xFFFFFFFFL);
            }
            if ((flags & FLAG_REQUEST_TYPE) != 0) {
                int rtOrd = buf.get() & 0xFF;
                if (rtOrd < RequestType.values().length) msg.setRequestType(RequestType.values()[rtOrd]);
            }
            if ((flags & FLAG_REQUEST_ID) != 0) msg.setRequestId(readLenPfx16(buf));
            if ((flags & FLAG_REPLY_TO_ID) != 0) msg.setReplyToId(readLenPfx16(buf));
            if ((flags & FLAG_THREAD_ID) != 0) msg.setThreadId(readLenPfx16(buf));
            if ((flags & FLAG_MESSAGE_ID) != 0) msg.setId(readLenPfx16(buf));
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Wire data ended mid-field", e);
        }

        // FIMP0V3 §3.5: nothing about the message is trusted until its author
        // is. The signature covers every byte before the trailer.
        int signedLength = buf.position();
        if (buf.remaining() != SIGNATURE_TRAILER_SIZE) {
            throw new IllegalArgumentException("Expected a " + SIGNATURE_TRAILER_SIZE
                    + "-byte signature trailer, found " + buf.remaining() + " bytes");
        }
        byte[] pubkey = new byte[33];
        byte[] signature = new byte[64];
        buf.get(pubkey);
        buf.get(signature);
        String signer;
        try {
            signer = KeyTools.pubkeyToFchAddr(pubkey);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Signature trailer carries an unreadable pubkey", e);
        }
        if (msg.getSenderId() == null || !msg.getSenderId().equals(signer)) {
            throw new IllegalArgumentException("Signed by " + signer + ", not by the sender it names");
        }
        boolean valid;
        try {
            valid = SchnorrSignature.schnorr_verify(signatureHash(wire, signedLength), pubkey, signature);
        } catch (RuntimeException e) {
            valid = false;
        }
        if (!valid) throw new IllegalArgumentException("Signature does not verify");

        return msg;
    }

    // ========== Body framing ==========

    /**
     * The plaintext layout inside the body:
     * {@code contentLen(4) | content | dataLen(4) | data}.
     *
     * <p>Both sections are always framed; an absent one is a zero length. This
     * is what a cipher seals, and what it returns on opening -- so the two
     * payloads are inside or outside the seal TOGETHER, never one without the
     * other.
     */
    public byte[] bodyFraming() {
        byte[] contentBytes = content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] dataBytes = data != null ? data : new byte[0];
        ByteBuffer buf = ByteBuffer.allocate(4 + contentBytes.length + 4 + dataBytes.length);
        buf.putInt(contentBytes.length);
        buf.put(contentBytes);
        buf.putInt(dataBytes.length);
        buf.put(dataBytes);
        return buf.array();
    }

    /**
     * Unframe a body into `content` and `data`.
     *
     * <p>A zero-length section reads back as null, not as ""/empty: the framing
     * cannot tell absent from empty, and every producer of an empty section
     * means absent.
     */
    public void applyBodyFraming(byte[] framing) {
        if (framing == null || framing.length < 8) {
            throw new IllegalArgumentException("Body framing too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(framing);
        try {
            int contentLength = buf.getInt();
            if (contentLength < 0 || contentLength > buf.remaining()) {
                throw new IllegalArgumentException(
                        "Body content length " + contentLength + " exceeds the framing");
            }
            byte[] contentBytes = new byte[contentLength];
            buf.get(contentBytes);

            int dataLength = buf.getInt();
            if (dataLength < 0 || dataLength > buf.remaining()) {
                throw new IllegalArgumentException(
                        "Body data length " + dataLength + " exceeds the framing");
            }
            byte[] dataBytes = new byte[dataLength];
            buf.get(dataBytes);

            this.content = contentLength == 0 ? null : new String(contentBytes, StandardCharsets.UTF_8);
            this.data = dataLength == 0 ? null : dataBytes;
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Body framing ended mid-field", e);
        }
    }

    /**
     * Whether this message is still sealed to us -- a body we hold but have not
     * opened. The cue for a locked row in the transcript, and for asking the
     * group for the key version it names.
     */
    public boolean isSealed() {
        return content == null && (data == null || data.length == 0)
                && body != null && body.length > 0;
    }

    // Wire format helpers

    /**
     * A symkeyVersion must fit the wire's unsigned 32 bits.
     *
     * <p><b>Refused rather than truncated.</b> A version is a lookup key:
     * wrapping one silently produces a message naming a key that cannot be
     * found, and the sender has no way to know. The range holds every mint
     * time until 2106.
     */
    private static void checkSymkeyVersion(Long version) {
        if (version != null && (version < 0L || version > 0xFFFFFFFFL)) {
            throw new IllegalStateException(
                    "symkeyVersion " + version + " does not fit the wire's unsigned 32 bits");
        }
    }

    private static void checkLen8(byte[] value, String field) {
        if (value != null && value.length > 0xFF) {
            throw new IllegalStateException(field + " exceeds the 255-byte length prefix");
        }
    }

    private static void checkLen16(byte[] value, String field) {
        if (value != null && value.length > 0xFFFF) {
            throw new IllegalStateException(field + " exceeds the 65535-byte length prefix");
        }
    }

    private static void writeLenPfx8(ByteBuffer buf, byte[] data) {
        buf.put((byte) (data != null ? data.length : 0));
        if (data != null && data.length > 0) buf.put(data);
    }

    private static String readLenPfx8(ByteBuffer buf) {
        int len = buf.get() & 0xFF;
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeLenPfx16(ByteBuffer buf, byte[] data) {
        buf.putShort((short) (data != null ? data.length : 0));
        if (data != null && data.length > 0) buf.put(data);
    }

    private static String readLenPfx16(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ========== Getters and setters ==========
    
    public ImType getType() {
        return type;
    }
    
    public void setType(ImType type) {
        this.type = type;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    
    public String getTargetId() {
        return targetId;
    }
    
    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }
    
    public Long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
    
    public Long getSequence() {
        return sequence;
    }
    
    public void setSequence(Long sequence) {
        this.sequence = sequence;
    }
    
    public ContentType getContentType() {
        return contentType;
    }
    
    public void setContentType(ContentType contentType) {
        this.contentType = contentType;
    }
    
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
    
    public RequestType getRequestType() {
        return requestType;
    }
    
    public void setRequestType(RequestType requestType) {
        this.requestType = requestType;
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
    public List<String> getRoadIds() {
        return roadIds;
    }
    
    public void setRoadIds(List<String> roadIds) {
        this.roadIds = roadIds;
    }
    
    public String getDockId() {
        return dockId;
    }
    
    public void setDockId(String dockId) {
        this.dockId = dockId;
    }
    
    public DeliveryMethod getDeliveryMethod() {
        return deliveryMethod;
    }
    
    public void setDeliveryMethod(DeliveryMethod deliveryMethod) {
        this.deliveryMethod = deliveryMethod;
    }
    
    public MessageStatus getStatus() {
        return status;
    }
    
    public void setStatus(MessageStatus status) {
        this.status = status;
    }
    
    public Long getDeliveredAt() {
        return deliveredAt;
    }
    
    public void setDeliveredAt(Long deliveredAt) {
        this.deliveredAt = deliveredAt;
    }
    
    public Long getReadAt() {
        return readAt;
    }
    
    public void setReadAt(Long readAt) {
        this.readAt = readAt;
    }
    
    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }
    
    public Long getSymkeyVersion() {
        return symkeyVersion;
    }
    
    public void setSymkeyVersion(Long symkeyVersion) {
        this.symkeyVersion = symkeyVersion;
    }
    
    public String getReplyToId() {
        return replyToId;
    }
    
    public void setReplyToId(String replyToId) {
        this.replyToId = replyToId;
    }
    
    public String getThreadId() {
        return threadId;
    }
    
    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }
    
    public String getSenderName() {
        return senderName;
    }
    
    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }
    
    public Boolean getUnread() {
        return unread;
    }
    
    public void setUnread(Boolean unread) {
        this.unread = unread;
    }
    
    public Boolean getPinned() {
        return pinned;
    }
    
    public void setPinned(Boolean pinned) {
        this.pinned = pinned;
    }
    
    public Boolean getDeleted() {
        return deleted;
    }
    
    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
