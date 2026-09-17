package com.fc.freer.onboarding;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Everything the checklist is decided from. Gathered by the caller so the decision itself,
 * {@link Onboarding}, is a pure function the tests can drive.
 */
public final class OnboardingFacts {

    /** A broadcast carve for a step that the chain does not show yet. */
    public static final class Pending {
        public final String txid;
        /** Older than {@link PendingIdentityCarve#OVERDUE_MS} without landing. */
        public final boolean overdue;

        public Pending(String txid, boolean overdue) {
            this.txid = txid;
            this.overdue = overdue;
        }
    }

    public boolean prikeyBackedUp;
    /** The live FID's record confirmed this session, or null when the chain has not answered. */
    public LiveFidRecord chain;
    public boolean guideIsContact;
    public boolean joinedSquare;
    public final Set<OnboardingStep> skipped = EnumSet.noneOf(OnboardingStep.class);
    /** Carves broadcast for REGISTER_CID, SET_HOME and JOIN_SQUARE that have not landed. */
    public final Map<OnboardingStep, Pending> pending = new EnumMap<>(OnboardingStep.class);

    public OnboardingFacts(boolean prikeyBackedUp, LiveFidRecord chain) {
        this.prikeyBackedUp = prikeyBackedUp;
        this.chain = chain;
    }
}
