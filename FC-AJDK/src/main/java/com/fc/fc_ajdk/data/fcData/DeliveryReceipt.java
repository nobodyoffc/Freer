package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.utils.JsonUtils;

import java.util.List;

/**
 * Delivery receipt for p2p messages.
 * Tracks delivery status and provides presence inference.
 */
public class DeliveryReceipt extends FcEntity {
    
    /**
     * Type of receipt.
     */
    public enum ReceiptType {
        DELIVERED,  // Message was delivered to recipient
        READ        // Message was read by recipient
    }
    
    private String messageId;          // ID of the message this receipt is for
    private String senderId;           // Who sent the original message
    private String recipientId;        // Who received and sent this receipt
    private ReceiptType receiptType;   // DELIVERED or READ
    private Long timestamp;            // When the receipt was generated
    
    // Delivery path info
    private DeliveryMethod deliveryMethod;
    private List<String> roadIds;      // If relayed via ROAD(s)
    private String dockId;             // If fetched from DOCK
    
    // Presence inference
    private Long lastOnlineAt;         // When was recipient last seen directly
    
    /**
     * Create a delivered receipt.
     */
    public static DeliveryReceipt delivered(String messageId, String senderId, 
                                            String recipientId, DeliveryMethod method) {
        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setId(messageId + "_delivered");
        receipt.setMessageId(messageId);
        receipt.setSenderId(senderId);
        receipt.setRecipientId(recipientId);
        receipt.setReceiptType(ReceiptType.DELIVERED);
        receipt.setTimestamp(System.currentTimeMillis());
        receipt.setDeliveryMethod(method);
        
        if (method != null && method.isOnline()) {
            receipt.setLastOnlineAt(receipt.getTimestamp());
        }
        
        return receipt;
    }
    
    /**
     * Create a read receipt.
     */
    public static DeliveryReceipt read(String messageId, String senderId, 
                                       String recipientId, DeliveryMethod method) {
        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setId(messageId + "_read");
        receipt.setMessageId(messageId);
        receipt.setSenderId(senderId);
        receipt.setRecipientId(recipientId);
        receipt.setReceiptType(ReceiptType.READ);
        receipt.setTimestamp(System.currentTimeMillis());
        receipt.setDeliveryMethod(method);
        
        if (method != null && method.isOnline()) {
            receipt.setLastOnlineAt(receipt.getTimestamp());
        }
        
        return receipt;
    }
    
    /**
     * Check if the recipient was online when this receipt was generated.
     */
    public boolean wasOnline() {
        return deliveryMethod != null && deliveryMethod.isOnline();
    }
    
    public String toJson() {
        return JsonUtils.toJson(this);
    }
    
    public static DeliveryReceipt fromJson(String json) {
        return JsonUtils.fromJson(json, DeliveryReceipt.class);
    }
    
    // Getters and setters
    
    public String getMessageId() {
        return messageId;
    }
    
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    
    public String getRecipientId() {
        return recipientId;
    }
    
    public void setRecipientId(String recipientId) {
        this.recipientId = recipientId;
    }
    
    public ReceiptType getReceiptType() {
        return receiptType;
    }
    
    public void setReceiptType(ReceiptType receiptType) {
        this.receiptType = receiptType;
    }
    
    public Long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
    
    public DeliveryMethod getDeliveryMethod() {
        return deliveryMethod;
    }
    
    public void setDeliveryMethod(DeliveryMethod deliveryMethod) {
        this.deliveryMethod = deliveryMethod;
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
    
    public Long getLastOnlineAt() {
        return lastOnlineAt;
    }
    
    public void setLastOnlineAt(Long lastOnlineAt) {
        this.lastOnlineAt = lastOnlineAt;
    }
}
