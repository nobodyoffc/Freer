package com.fc.freer.manager;

import androidx.annotation.NonNull;

public abstract class FcManager {
    public enum ManagerType{
        TEST,
        ACCOUNT,
        CASH,
        CONTACT,
        MULTISIGN,
        SECRET,
        KEY_INFO,
        AVATAR,
        CID;
        @NonNull
        @Override
        public String toString() {
            return this.name();
        }

        // New method to check if a string matches a ServiceType
        public static ManagerType fromString(String input) {
            if (input == null) {
                return null;
            }
            for (ManagerType type : ManagerType.values()) {
                if (type.name().equalsIgnoreCase(input)) {
                    return type;
                }
            }
            return null;
        }
    }
}
