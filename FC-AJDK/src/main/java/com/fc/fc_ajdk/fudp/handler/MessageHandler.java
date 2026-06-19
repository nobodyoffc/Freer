package com.fc.fc_ajdk.fudp.handler;

import com.fc.fc_ajdk.fudp.message.*;
import com.fc.fc_ajdk.fudp.node.NodeEventListener;
import com.fc.fc_ajdk.fudp.metrics.MeterDirection;
import com.fc.fc_ajdk.fudp.metrics.MeterRecord;
import com.fc.fc_ajdk.utils.TimberLogger;


import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


/**
 * Routes incoming messages to appropriate handlers.
 */
public class MessageHandler {
    private static final String TAG = "MessageHandler";

    /**
     * Interface for sending messages (to avoid circular dependency with FudpNode)
     */
    public interface MessageSender {
        void sendMessage(String peerId, AppMessage message) throws IOException;
    }

    private volatile NodeEventListener eventListener;
    private final Map<Long, CompletableFuture<ResponseMessage>> pendingRequests;
    private final Map<Long, CompletableFuture<PongMessage>> pendingPongs;
    private final Consumer<MeterRecord> meterSink;
    private MessageSender messageSender;

    public MessageHandler(NodeEventListener eventListener, MessageSender messageSender, Consumer<MeterRecord> meterSink) {
        this.eventListener = eventListener;
        this.pendingRequests = new ConcurrentHashMap<>();
        this.pendingPongs = new ConcurrentHashMap<>();
        this.messageSender = messageSender;
        this.meterSink = meterSink;
    }

    /**
     * Set message sender (called by FudpNode after initialization)
     */
    public void setMessageSender(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    /**
     * Update the event listener without losing pending requests or pongs.
     */
    public void setEventListener(NodeEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * Handle incoming data from a peer.
     *
     * @param peerId       the sender's FID
     * @param connectionId the connection ID the data arrived on
     * @param data         the raw message bytes
     */
    public void handleIncomingData(String peerId, long connectionId, byte[] data) {
        try {
            AppMessage message = MessageCodec.decode(data);
            emitMeter(peerId, message.getType(), data.length, MeterDirection.INBOUND);
            routeMessage(peerId, connectionId, message);
        } catch (IllegalArgumentException e) {
            TimberLogger.e(TAG, "[MessageHandler] Failed to decode message from %s (dataLen=%d): %s",
                    peerId, data != null ? data.length : 0, e.getMessage());
            if (eventListener != null) {
                eventListener.onError(peerId, 2002, "Invalid message: " + e.getMessage());
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "[MessageHandler] Error handling message from %s: %s", peerId, e.getMessage());
            if (eventListener != null) {
                eventListener.onError(peerId, 1, "Error handling message: " + e.getMessage());
            }
        }
    }

    /**
     * Handle incoming data (convenience overload without connectionId, uses 0 as default).
     */
    public void handleIncomingData(String peerId, byte[] data) {
        handleIncomingData(peerId, 0L, data);
    }

    private void emitMeter(String peerId, MessageType type, int payloadBytes, MeterDirection direction) {
        if (meterSink == null) return;
        try {
            meterSink.accept(MeterRecord.builder()
                    .peerId(peerId)
                    .messageType(type)
                    .direction(direction)
                    .payloadBytes(payloadBytes)
                    .receiveTimestampMillis(System.currentTimeMillis())
                    .sendTimestampMillis(0)
                    .retransmitCount(0)
                    .build());
        } catch (Exception ignored) {
            // keep transport path resilient
        }
    }

    /**
     * Route message to appropriate handler.
     */
    private void routeMessage(String peerId, long connectionId, AppMessage message) {
        switch (message.getType()) {
            case REQUEST -> handleRequest(peerId, connectionId, (RequestMessage) message);
            case RESPONSE -> handleResponse(peerId, (ResponseMessage) message);
            case ERROR -> handleError(peerId, (ErrorMessage) message);
            case PING -> handlePing(peerId, (PingMessage) message);
            case PONG -> handlePong(peerId, (PongMessage) message);
            // NOTIFY and NOTIFY_ACK are handled directly by FudpNode
            default -> {
                if (eventListener != null) {
                    eventListener.onError(peerId, 2001, "Unhandled message type: " + message.getType());
                }
            }
        }
    }

    /**
     * Handle incoming request (as provider).
     */
    private void handleRequest(String peerId, long connectionId, RequestMessage request) {
        if (eventListener != null) {
            eventListener.onRequestReceived(
                    peerId,
                    connectionId,
                    request.getMessageId(),
                    request.getSid(),
                    request.getData()
            );
        }
    }

    /**
     * Handle incoming response (as consumer).
     */
    private void handleResponse(String peerId, ResponseMessage response) {
        long responseId = response.getMessageId();
        CompletableFuture<ResponseMessage> future = pendingRequests.remove(responseId);
        if (future != null) {
            future.complete(response);
        } else {
        }
    }

    /**
     * Handle error message.
     */
    private void handleError(String peerId, ErrorMessage error) {
        int errorCode = error.getErrorCode();
        String errorMessage = error.getErrorMessage();

        TimberLogger.e(TAG, "Error received from peer %s: code=%d, message=%s",
            peerId, errorCode, errorMessage);

        CompletableFuture<ResponseMessage> future = pendingRequests.remove(error.getMessageId());
        if (future != null) {
            RuntimeException exception = new RuntimeException(
                String.format("Error %d: %s", errorCode, errorMessage)
            );
            future.completeExceptionally(exception);
        }

        if (eventListener != null) {
            eventListener.onError(peerId, errorCode, errorMessage);
        }
    }

    /**
     * Handle ping message.
     */
    private void handlePing(String peerId, PingMessage ping) {
        // Pong is sent by FudpNode
    }

    /**
     * Handle pong message.
     */
    private void handlePong(String peerId, PongMessage pong) {
        TimberLogger.i(TAG, "[MessageHandler] handlePong: received PONG from %s msgId=%d rtt=%dms",
                peerId, pong.getMessageId(), pong.calculateRtt());
        long rtt = pong.calculateRtt();
        if (eventListener != null) {
            eventListener.onPingComplete(peerId, rtt);
            byte[] data = pong.getData();
            if (data != null && data.length > 0) {
                eventListener.onPongInfo(peerId, data);
            }
        }

        CompletableFuture<PongMessage> future = pendingPongs.remove(pong.getMessageId());
        if (future != null && !future.isDone()) {
            future.complete(pong);
            TimberLogger.d(TAG, "[MessageHandler] handlePong: completed pending future for msgId=%d", pong.getMessageId());
        } else {
            TimberLogger.w(TAG, "[MessageHandler] handlePong: no pending future for msgId=%d (future=%s, isDone=%s)",
                    pong.getMessageId(), future, future != null ? future.isDone() : "N/A");
        }
    }

    /**
     * Register a pending request.
     */
    public void registerPendingRequest(long messageId, CompletableFuture<ResponseMessage> future) {
        pendingRequests.put(messageId, future);
        TimberLogger.d(TAG, "[MessageHandler] Registered pending request (messageId=%d, totalPending=%d)",
                messageId, pendingRequests.size());
    }

    /**
     * Cancel a pending request by removing from pending map.
     * Note: Does not cancel the future - caller should complete it exceptionally if needed.
     * This prevents CancellationException from being thrown instead of the intended exception.
     */
    public void cancelPendingRequest(long messageId) {
        pendingRequests.remove(messageId);
        // Don't call future.cancel(true) - let caller complete it exceptionally
        // Otherwise CancellationException will be thrown instead of TimeoutException
    }

    /**
     * Get pending request count.
     */
    public int getPendingRequestCount() {
        return pendingRequests.size();
    }

    /**
     * Await a pong matching the given messageId.
     */
    public CompletableFuture<PongMessage> awaitPong(long messageId) {
        CompletableFuture<PongMessage> future = new CompletableFuture<>();
        pendingPongs.put(messageId, future);
        return future;
    }

    /**
     * Cancel waiting for a pong by removing from pending map.
     * Note: Does not cancel the future - caller should complete it exceptionally if needed.
     * This prevents CancellationException from being thrown instead of the intended exception.
     */
    public void cancelPong(long messageId) {
        pendingPongs.remove(messageId);
        // Don't call f.cancel(true) - let caller complete it exceptionally
        // Otherwise CancellationException will be thrown instead of TimeoutException
    }

    /**
     * Send response (as provider).
     */
    public void sendResponse(String peerId, long requestId, int statusCode, byte[] data) throws IOException {
        if (messageSender == null) {
            throw new IllegalStateException("MessageSender not set");
        }

        ResponseMessage response = new ResponseMessage(requestId, statusCode, data);
        messageSender.sendMessage(peerId, response);
    }
}
