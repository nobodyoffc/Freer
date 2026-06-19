package com.fc.fc_ajdk.fudp.connection;

/**
 * Connection state machine
 */
public enum ConnectionState {
    IDLE,         // Initial state
    ESTABLISHING, // First packet sent/received
    ESTABLISHED,  // Connection active
    CLOSING,      // FIN sent/received
    CLOSED        // Connection closed
}
