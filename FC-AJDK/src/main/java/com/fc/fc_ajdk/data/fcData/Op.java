package com.fc.fc_ajdk.data.fcData;



public enum Op {
    PING((byte) 0),
    PONG((byte) 1),

    SIGN((byte) 2),
    VERIFY((byte) 3),
    ENCRYPT((byte) 4),
    DECRYPT((byte) 5),
    NOTIFY((byte)6),

    SIGN_IN((byte) 10),
    ASK_KEY((byte)11),
    SHARE_KEY((byte)12),

    UPDATE_DATA((byte)13),
    ASK_DATA((byte) 14),
    SHARE_DATA((byte) 15),

    ASK_HAT((byte) 16),
    SHARE_HAT((byte) 17),

    SHOW((byte) 18),
    GO((byte) 19),
    PAY((byte)20),

    SEND((byte)21),
    DELETE((byte)22),
    RECOVER((byte)23),

    ADD((byte)24),
    UPDATE((byte)25),


    EXIT((byte)99);


    public String toLowerCase() {
        return this.name().toLowerCase();
    }

    public final byte number;
    Op(byte number) {this.number=number;}

    public static final String ENCRYPT_STR = "encrypt";
    public static final String DECRYPT_STR = "decrypt";
    public static final String NOTIFY_STR = "notify";
    public static final String SIGN_STR = "sign";
    public static final String VERIFY_STR = "verify";
    public static final String SIGN_IN_STR = "sign_in";
    public static final String ASK_KEY_STR = "ask_key";
    public static final String SHARE_KEY_STR = "share_key";
    public static final String UPDATE_DATA_STR = "update_data";
    public static final String ASK_DATA_STR = "ask_data";
    public static final String SHARE_DATA_STR = "share_data";
    public static final String ASK_HAT_STR = "ask_hat";
    public static final String SHARE_HAT_STR = "share_hat";
    public static final String SHOW_STR = "show";
    public static final String GO_STR = "go";
    public static final String PAY_STR = "pay";
    public static final String SEND_STR = "send";
    public static final String DELETE_STR = "delete";
    public static final String RECOVER_STR = "recover";
    public static final String ADD_STR = "add";
    public static final String UPDATE_STR = "update";
    public static final String EXIT_STR = "exit";
}
