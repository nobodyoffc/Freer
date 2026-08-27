package com.fc.freer.utils;

/**
 * Shared intent keys for launching a list activity (ProtocolActivity / ServiceActivity /
 * CodeActivity) as a picker and receiving the chosen entity id(s) back.
 *
 * <p>{@link #EXTRA_CHOOSE_MODE} carries a {@link ChooseMode} name:
 * {@link ChooseMode#WITHOUT_CHOOSE} (normal browsing, returns nothing),
 * {@link ChooseMode#CHOOSE_ONE} (single-select radio, returns {@link #EXTRA_SELECTED_ID}) or
 * {@link ChooseMode#CHOOSE_MULTI} (multi-select, returns {@link #EXTRA_SELECTED_IDS}).</p>
 */
public final class EntityChooser {
    public static final String EXTRA_CHOOSE_MODE = "entity_choose_mode";
    /** Result extra: the single chosen id (CHOOSE_ONE). */
    public static final String EXTRA_SELECTED_ID = "entity_selected_id";
    /** Result extra ({@code ArrayList<String>}): the chosen ids (CHOOSE_MULTI). */
    public static final String EXTRA_SELECTED_IDS = "entity_selected_ids";

    private EntityChooser() {}

    public static ChooseMode parseChooseMode(String modeStr) {
        if (modeStr == null) return ChooseMode.WITHOUT_CHOOSE;
        try {
            return ChooseMode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            return ChooseMode.WITHOUT_CHOOSE;
        }
    }
}
