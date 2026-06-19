package com.fc.fc_ajdk.data.feipData;

public enum ComponentType {

    BASE,
    MAP,
    ROAD,
    DOCK;

    @Override
    public String toString() {
        return this.name();
    }

    // New method to check if a string matches a ServiceType
    public static ComponentType fromString(String input) {
        if (input == null) {
            return null;
        }
        for (ComponentType type : ComponentType.values()) {
            if (type.name().equalsIgnoreCase(input)) {
                return type;
            }
        }
        return null;
    }
}
