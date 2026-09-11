package com.fc.freer.nobody;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.freer.im.NobodyBoard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * The one place the app remembers which FIDs are nobodies.
 *
 * A nobody is a FID whose private key has been published on chain: anyone can
 * sign as it, open what is sealed to it, spend its cash and rewrite its on-chain
 * data. Publishing a key cannot be undone, so a nobody stays a nobody forever —
 * positives are persisted and never expire. A "not a nobody" answer is only true
 * until the key is published, so negatives live in memory for a short while.
 *
 * Every freer or nobody-index lookup feeds this registry through
 * {@code FapiClient.NobodyObserver}; screens only read it. Reading never touches
 * the network or a database, so it is safe on the UI thread.
 */
public final class NobodyRegistry {

    /** Persistence for the positives and the one-time own-key alerts. */
    public interface Store {
        Set<String> loadNobodies();

        void saveNobody(String fid);

        boolean isAlerted(String fid);

        void setAlerted(String fid);
    }

    /** Told, on the listener executor, which FIDs were newly learned to be nobodies. */
    public interface Listener {
        void onNobodiesLearned(Set<String> fids);
    }

    /**
     * Checks FIDs against the nobody index.
     *
     * @return the FIDs found there (keys of the result), or null when the check failed
     */
    public interface Resolver {
        Map<String, ?> checkNobodies(List<String> fids) throws Exception;
    }

    static final long NEGATIVE_TTL_MS = 10 * 60 * 1000L;
    static final long FAILURE_BACKOFF_MS = 60 * 1000L;

    private static volatile NobodyRegistry instance;

    private final Store store;
    private final LongSupplier clock;
    private final Executor listenerExecutor;
    private final Set<String> nobodies = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> notNobodyUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    NobodyRegistry(Store store, LongSupplier clock, Executor listenerExecutor) {
        this.store = store;
        this.clock = clock;
        this.listenerExecutor = listenerExecutor;
        nobodies.add(NobodyBoard.DEFAULT_NOBODY_FID);
        Set<String> saved = store.loadNobodies();
        if (saved != null) nobodies.addAll(saved);
    }

    /** Install the app-wide registry. Entries learned before installation are kept. */
    public static synchronized NobodyRegistry install(Store store, Executor listenerExecutor) {
        NobodyRegistry registry = new NobodyRegistry(store, System::currentTimeMillis, listenerExecutor);
        NobodyRegistry previous = instance;
        if (previous != null) {
            registry.markNobodies(previous.nobodies);
            registry.listeners.addAll(previous.listeners);
        }
        instance = registry;
        return registry;
    }

    /** The app-wide registry; an unpersisted one until {@link #install} runs. */
    public static NobodyRegistry get() {
        NobodyRegistry registry = instance;
        if (registry != null) return registry;
        synchronized (NobodyRegistry.class) {
            if (instance == null) {
                instance = new NobodyRegistry(new MemoryStore(), System::currentTimeMillis, Runnable::run);
            }
            return instance;
        }
    }

    public boolean isNobody(String fid) {
        return fid != null && nobodies.contains(fid);
    }

    /** Whether the FID of this pubkey is a known nobody; false for anything that isn't a pubkey. */
    public boolean isNobodyPubkey(String pubkey) {
        String fid = fidOfPubkey(pubkey);
        return fid != null && isNobody(fid);
    }

    static String fidOfPubkey(String pubkey) {
        if (pubkey == null || !KeyTools.isPubkey(pubkey)) return null;
        try {
            return KeyTools.pubkeyToFchAddr(pubkey);
        } catch (Exception e) {
            return null;
        }
    }

    public void mark(String fid) {
        if (fid != null) markNobodies(Collections.singletonList(fid));
    }

    public void markNobodies(Collection<String> fids) {
        if (fids == null) return;
        Set<String> learned = new LinkedHashSet<>();
        for (String fid : fids) {
            if (fid == null || fid.isEmpty()) continue;
            notNobodyUntil.remove(fid);
            if (nobodies.add(fid)) {
                store.saveNobody(fid);
                learned.add(fid);
            }
        }
        if (learned.isEmpty() || listeners.isEmpty()) return;
        Set<String> snapshot = Collections.unmodifiableSet(learned);
        listenerExecutor.execute(() -> {
            for (Listener listener : listeners) listener.onNobodiesLearned(snapshot);
        });
    }

    public void markNotNobodies(Collection<String> fids) {
        if (fids == null) return;
        long until = clock.getAsLong() + NEGATIVE_TTL_MS;
        for (String fid : fids) {
            if (fid == null || fid.isEmpty() || nobodies.contains(fid)) continue;
            notNobodyUntil.put(fid, until);
            failedUntil.remove(fid);
        }
    }

    /** Whether a recent nobody-index check found this FID absent. */
    public boolean isKnownNotNobody(String fid) {
        if (fid == null || nobodies.contains(fid)) return false;
        Long until = notNobodyUntil.get(fid);
        return until != null && until > clock.getAsLong();
    }

    /** FIDs, deduplicated and in order, whose status is neither known nor freshly checked. */
    public List<String> unknownOf(Collection<String> fids) {
        List<String> unknown = new ArrayList<>();
        if (fids == null) return unknown;
        Set<String> seen = new HashSet<>();
        for (String fid : fids) {
            if (fid == null || fid.isEmpty() || !seen.add(fid)) continue;
            if (!isNobody(fid) && !isKnownNotNobody(fid)) unknown.add(fid);
        }
        return unknown;
    }

    /** The known nobodies among these FIDs, deduplicated and in order. */
    public List<String> nobodiesOf(Collection<String> fids) {
        List<String> found = new ArrayList<>();
        if (fids == null) return found;
        Set<String> seen = new HashSet<>();
        for (String fid : fids) {
            if (fid != null && seen.add(fid) && isNobody(fid)) found.add(fid);
        }
        return found;
    }

    /**
     * Check the unknown FIDs against the nobody index. Blocking — call it off the
     * UI thread.
     *
     * @param retryFailed also retry FIDs whose last check failed moments ago; a
     *                    confirmation before a risky action wants that, a list
     *                    bind does not
     * @return false only when a needed check failed
     */
    public boolean resolve(Collection<String> fids, Resolver resolver, boolean retryFailed) {
        long now = clock.getAsLong();
        List<String> toCheck = new ArrayList<>();
        for (String fid : unknownOf(fids)) {
            if (!retryFailed) {
                Long until = failedUntil.get(fid);
                if (until != null && until > now) continue;
                if (!inFlight.add(fid)) continue;
            } else {
                inFlight.add(fid);
            }
            toCheck.add(fid);
        }
        if (toCheck.isEmpty()) return true;
        try {
            Map<String, ?> found = resolver.checkNobodies(toCheck);
            if (found == null) {
                markFailed(toCheck);
                return false;
            }
            List<String> positives = new ArrayList<>();
            List<String> negatives = new ArrayList<>();
            for (String fid : toCheck) {
                if (found.containsKey(fid)) positives.add(fid);
                else negatives.add(fid);
            }
            markNobodies(positives);
            markNotNobodies(negatives);
            return true;
        } catch (Exception e) {
            markFailed(toCheck);
            return false;
        } finally {
            inFlight.removeAll(toCheck);
        }
    }

    private void markFailed(List<String> fids) {
        long until = clock.getAsLong() + FAILURE_BACKOFF_MS;
        for (String fid : fids) failedUntil.put(fid, until);
    }

    /**
     * Claim the one-time alert that one of the user's own keys is a nobody.
     *
     * @return true the first time for this FID, false ever after
     */
    public synchronized boolean claimOwnKeyAlert(String fid) {
        if (fid == null || !isNobody(fid) || store.isAlerted(fid)) return false;
        store.setAlerted(fid);
        return true;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Unpersisted store, used before installation and in tests. */
    static final class MemoryStore implements Store {
        private final Set<String> nobodies = ConcurrentHashMap.newKeySet();
        private final Set<String> alerted = ConcurrentHashMap.newKeySet();

        @Override
        public Set<String> loadNobodies() {
            return new HashSet<>(nobodies);
        }

        @Override
        public void saveNobody(String fid) {
            nobodies.add(fid);
        }

        @Override
        public boolean isAlerted(String fid) {
            return alerted.contains(fid);
        }

        @Override
        public void setAlerted(String fid) {
            alerted.add(fid);
        }
    }
}
