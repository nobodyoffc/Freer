package com.fc.freer.data;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.HomeServiceResolver;
import com.fc.fc_ajdk.utils.Hex;

import java.util.Locale;
import java.util.Map;

/**
 * The main FID's {@code home.BASE}: the FAPI server (the FAPI11 BASE component) its owner reads
 * the chain through, on every device they use. {@code ApiCenter} follows it at launch.
 * <p>
 * <b>Following it starts from a server that is not it.</b> A device has to read the chain to
 * learn the home, so it connects somewhere first — the built-in list, or the last home BASE it
 * reached — and only then knows where to go. That first server also answers the home lookup and
 * the service record, so it is trusted for those; what is checked here is the step after, where
 * a HELLO over plain UDP hands back whatever key the machine at that address chose. That key
 * must be the one the service record names, or a hijacked address could carry the session.
 * <p>
 * The same rules as the Mac app's {@code HomeBase}: follow only a service id (an address has no
 * record to check), only when the key checks out, only the main FID's home, and never at the
 * cost of a working connection.
 */
public final class HomeBaseManager {
    private HomeBaseManager() {}

    public enum Kind { NONE, SERVICE_ID, ADDRESS, UNREADABLE }

    /** What the main FID's home says about its BASE. {@code value} is the SID or the address. */
    public static final class Entry {
        public final Kind kind;
        public final String value;

        Entry(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }
    }

    /** The BASE in {@code home}, opened with {@code prikey} when it is sealed. */
    public static Entry entry(Map<String, String> home, byte[] prikey) {
        String stored = HomePrivacy.valueOf(home, "BASE");
        if (stored == null) return new Entry(Kind.NONE, null);
        String opened = HomePrivacy.open(stored, prikey);
        if (opened == null) return new Entry(Kind.UNREADABLE, null);
        String sid = HomeServiceResolver.extractSid(opened);
        if (sid != null) return new Entry(Kind.SERVICE_ID, sid);
        return new Entry(Kind.ADDRESS, opened);
    }

    public enum Verdict { MATCHES, MISMATCH, UNVERIFIABLE }

    /**
     * Whether the key a server answered HELLO with is the one its service record names. A FAPI
     * server's FUDP key is its dealer's — FC-JDK's {@code ClientGroup} records the discovered key
     * as the dealer pubkey — so the record's {@code dealerPubkey} is compared, or failing that,
     * the FID the key derives to against its {@code dealer}.
     */
    public static Verdict verify(byte[] helloPubkey, Service record) {
        if (record == null || helloPubkey == null) return Verdict.UNVERIFIABLE;
        String hello = Hex.toHex(helloPubkey).toLowerCase(Locale.ROOT);
        String expected = record.getDealerPubkey();
        if (expected != null && !expected.trim().isEmpty()) {
            return expected.trim().toLowerCase(Locale.ROOT).equals(hello) ? Verdict.MATCHES : Verdict.MISMATCH;
        }
        String dealer = record.getDealer();
        if (dealer != null && !dealer.trim().isEmpty()) {
            String fid;
            try {
                fid = KeyTools.pubkeyToFchAddr(helloPubkey);
            } catch (Exception e) {
                return Verdict.MISMATCH;
            }
            return dealer.trim().equals(fid) ? Verdict.MATCHES : Verdict.MISMATCH;
        }
        return Verdict.UNVERIFIABLE;
    }
}
