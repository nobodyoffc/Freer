package com.fc.freer.onboarding;

import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * One step of the getting-started checklist, in the order it is shown.
 * <p>
 * {@link #key} is persisted in the Setting (see {@link #joinKeys}), and matches the Mac
 * build's names, so it must not change.
 * <p>
 * A master is deliberately not a step: it names another FID of your own, which a beginner
 * does not have. Setting one does tick {@link #BACKUP_PRIKEY} off, because the carve puts the
 * prikey on the chain — see {@link Onboarding#getBackupMaster()}.
 */
public enum OnboardingStep {
    BACKUP_PRIKEY("backupPrikey"),
    FIRST_FCH("firstFch"),
    REGISTER_CID("registerCid"),
    SET_HOME("setHome"),
    ADD_GUIDE("addGuide"),
    JOIN_SQUARE("joinSquare");

    public final String key;

    OnboardingStep(String key) {
        this.key = key;
    }

    /**
     * The first four are what make an identity work at all. The last two are being part of
     * the place, which nobody should be made to do.
     */
    public boolean isSkippable() {
        return this == ADD_GUIDE || this == JOIN_SQUARE;
    }

    public static OnboardingStep fromKey(String key) {
        if (key == null) return null;
        for (OnboardingStep step : values()) {
            if (step.key.equals(key.trim())) return step;
        }
        return null;
    }

    /** Parse a comma-joined set of keys. A name this build does not know is dropped. */
    public static Set<OnboardingStep> parseKeys(String joined) {
        Set<OnboardingStep> steps = EnumSet.noneOf(OnboardingStep.class);
        if (joined == null || joined.isEmpty()) return steps;
        for (String part : joined.split(",")) {
            OnboardingStep step = fromKey(part);
            if (step != null) steps.add(step);
        }
        return steps;
    }

    /** The keys of {@code steps}, sorted and comma-joined, as {@link #parseKeys} reads them. */
    public static String joinKeys(Set<OnboardingStep> steps) {
        TreeSet<String> keys = new TreeSet<>();
        if (steps != null) {
            for (OnboardingStep step : steps) keys.add(step.key);
        }
        return String.join(",", keys);
    }
}
