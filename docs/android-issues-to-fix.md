---
title: Freer Android — Issues to Fix
owner: user
status: living document
---

# Freer Android — Issues to Fix

Running log of bugs, smells, and risky patterns spotted in the Android codebase while planning / porting Freer to macOS (Swift/SwiftUI). Entries are added as they are discovered; fix them in the Android project when convenient.

## Conventions

- **Severity** — `high` (security / correctness), `medium` (latent bug / maintenance), `low` (style / minor).
- **Scope** — `port` means this must be fixed on the Mac side too; `android-only` means it does not affect the rewrite (e.g. APIP-related, since APIP is being retired); `both` means fix in both codebases.
- File paths are relative to the Android repo root (`/Users/liuchangyong/AndroidStudioProjects/Freer`).
- Append new entries at the bottom of the relevant section; do not rewrite history.

---

## Security

### S1. Hardcoded test secret in production class `KeyTools`
- **Severity:** high
- **Scope:** android-only (remove from production build)
- **Location:** `FC-AJDK/src/main/java/.../core/crypto/KeyTools.java:35` — literal `"春花秋月何时了"`.
- **Problem:** A hardcoded secret sits in a class loaded at runtime. Even if only used by a `main()` demo, it ships in the APK and can be grepped from a decompiled build.
- **Fix:** Move the demo `main()` into `src/test/java` as a real unit test, or delete it.

### S2. Hardcoded test password in legacy `EccAes256K1P7`
- **Severity:** medium
- **Scope:** android-only
- **Location:** `FC-AJDK/src/main/java/.../core/crypto/old/EccAes256K1P7.java` — literal `"password马云！"`.
- **Problem:** Same as S1. Additionally the whole `core/crypto/old/` folder looks like archived legacy code shipped in production.
- **Fix:** If the `old/` package is truly unused, delete it. Otherwise move demos to tests.

### S3. JCE key-length reflection hack
- **Severity:** medium
- **Scope:** android-only (Mac version uses CryptoKit directly)
- **Location:** `TxHandler.fixKeyLength()` (reflection into `javax.crypto.JceSecurity`).
- **Problem:** Bypasses JVM export-grade crypto restrictions by reflection. Fragile across JDK versions and Android runtimes. On modern Android (minSdk 28, target 34) the policy is already unlimited, so the whole routine is almost certainly dead code.
- **Fix:** Remove the method and its call sites; add a unit test that does a 256-bit AES op to prove it still works.

### S4. `char[]` password passes through `String`
- **Severity:** medium
- **Scope:** both (audit on Swift side too — prefer `Data` / zeroizable buffers, never `String`)
- **Location:** KDF call sites in `FC-AJDK/.../core/crypto/` (Argon2, HKDF).
- **Problem:** Several code paths accept `char[]` but internally build a `String`, which lands in the interned string pool and cannot be zeroized. Defeats the reason for using `char[]`.
- **Fix:** Keep passwords as `char[]` end-to-end, encode to bytes with `CharBuffer` → `ByteBuffer` → explicit `byte[]`, then `Arrays.fill(buf, (byte)0)` after use.

### S5. Error messages may leak plaintext / key material
- **Severity:** medium
- **Scope:** both
- **Location:** `CryptoDataByte.setCodeMessage(...)` call sites in `FC-AJDK/.../core/crypto/`.
- **Problem:** Broad `catch (Exception e)` blocks shove `e.getMessage()` onto a user-visible field. If a cipher implementation includes partial input or a key handle in its message, it leaks.
- **Fix:** Translate exceptions to a fixed enum of error codes; never propagate exception messages to user-facing fields.

### S6. Non-constant-time MAC comparison in HMAC verify path
- **Severity:** high
- **Scope:** android-only
- **Location:** `FC-AJDK/src/main/java/.../core/crypto/CryptoDataByte.java:1188,1212`
- **Problem:** MAC check is `!newSumHex.equals(sumHex)` — `String.equals` on hex-encoded MACs is not constant-time. An attacker observing decrypt-timing can learn which byte of the MAC differs first and walk a forgery one byte at a time (classic timing attack). Applies to every ciphertext delivered over a network path the attacker can probe.
- **Fix:** Compare the **raw** MAC bytes with `java.security.MessageDigest.isEqual(a, b)`, which is explicitly constant-time. Never compare hex strings, and never short-circuit.

### S7. AES-CBC HMAC key equals the AES encryption key
- **Severity:** medium
- **Scope:** android-only
- **Location:** `FC-AJDK/src/main/java/.../core/crypto/CryptoDataByte.java:1130` (`makeSum4()`)
- **Problem:** `sum = SHA256(symkey || iv || did)[0..4]` — the AES cipher key is fed directly into the MAC. Reusing one key for confidentiality and authenticity violates key separation. Realistic exploitation is hard, but this pattern disqualifies the construction from any formal-analysis guarantee and has no upside.
- **Fix:** Derive two 32-byte subkeys from the master via HKDF-Expand with distinct `info` strings (e.g. `"fc-aes-enc"`, `"fc-aes-mac"`). Use one for AES, the other for HMAC-SHA256.

### S9. Phrase → private key uses plain SHA-256, no salt, no KDF cost
- **Severity:** high
- **Scope:** both (Mac port must decide between matching this or upgrading)
- **Location:** `app/src/main/java/com/fc/freer/myKeys/CreateKeyByPhraseActivity.java:109,161` — `byte[] priKey32 = Hash.sha256(phrase.getBytes());`
- **Problem:** A wallet-private-key derived from a user-typed phrase via a single round of SHA-256 is grindable. SHA-256 runs at billions/sec on a GPU. A 6-8 word phrase out of a realistic human-picked distribution is already well within attacker grind range; a weaker phrase is hopeless. There is no salt, so an attacker can build a rainbow table over common phrases once and reuse it across every user that ever picks from that set.
- **Fix:** Use Argon2id with the project-standard params (iter=3, mem=64 MiB, par=1, 32-byte output). Since the derivation must be deterministic (same phrase → same key so the user can recover), the salt must be a *protocol-wide constant* rather than per-user random. A protocol-constant salt does **not** restore full dictionary resistance (everyone shares the same salt), but the 64 MiB memory cost raises the per-guess cost from nanoseconds to ~300 ms — a ~10⁸× slowdown for a grinder — which is the best we can do without making the derivation non-deterministic.
- **Compat note:** Changing the derivation breaks recovery of keys created under the old scheme. If kept, at least gate behind a version byte so future migrations are possible.

### S8. AES-CBC MAC truncated to 4 bytes
- **Severity:** medium
- **Scope:** android-only
- **Location:** `FC-AJDK/src/main/java/.../core/crypto/CryptoDataByte.java:1131` (`makeSum4()`)
- **Problem:** HMAC-SHA256 output is truncated to 4 bytes — ~32 bits of authentication strength. Generic forgery requires ~2³² ciphertexts, feasible for a persistent attacker, and trivial for grinding. Accepted standard is ≥16 bytes (128 bits).
- **Fix:** Extend to 16 bytes minimum. If payload overhead is the justification, document the threat model this is acceptable under — otherwise extend and bump the wire-format version byte.

---

## Silent failures (catch-and-ignore)

### F1. `catch (Exception ignored) {}` in networking & IM
- **Severity:** high (these are in hot paths)
- **Scope:** both (Swift side must use `try`/`throws` and surface or log)
- **Locations (non-exhaustive):**
  - `FC-AJDK/.../fapi/client/FapiClient.java:1190`
  - `FC-AJDK/.../fapi/client/FapiClient.java:1372`
  - `FC-AJDK/.../fapi/client/FapiClient.java:1454`
  - `FC-AJDK/.../core/crypto/Decryptor.java:277` (InterruptedException; does not call `Thread.currentThread().interrupt()`)
  - `FC-AJDK/.../core/crypto/Decryptor.java:547` (same)
  - `app/src/main/java/com/fc/freer/im/ImManager.java` (~10 sites)
  - `app/src/main/java/com/fc/freer/im/FileShareHelper.java`
  - `app/src/main/java/com/fc/freer/data/UploadService.java`
- **Fix:** Replace every `ignored` with `Timber.w(e, "<context>")` at minimum. Re-raise where recovery is not possible. For `InterruptedException`, always call `Thread.currentThread().interrupt()` before returning.

---

## Correctness / latent bugs

### C1. Unbounded ECDH cache with non-LRU eviction
- **Severity:** medium
- **Scope:** android-only (Mac impl will use a proper LRU)
- **Location:** `FC-AJDK/.../fudp/CryptoManager.java` — `ecdhCache`.
- **Problem:** Map grows to 1000 entries then evicts arbitrarily. Not LRU, so frequently-used peers can be evicted while stale ones linger, causing session renegotiation.
- **Fix:** Use `LinkedHashMap(initialCapacity, 0.75f, /*accessOrder=*/true)` inside `synchronized`, or switch to `androidx.collection.LruCache<String, EcdhEntry>`.

### C2. `@Nullable` public fields without validation
- **Severity:** medium
- **Scope:** android-only (APIP-related classes — APIP is being retired, but some shapes may reappear in FUDP/FAPI)
- **Location:** `FC-AJDK/.../data/apipData/` (`RequestBody`, `Fcdsl`, etc.)
- **Problem:** Public nullable fields are passed into crypto / signing code without null checks, producing opaque NPEs in low-level methods.
- **Fix:** Either make fields non-null with sensible defaults, or validate at the RequestBody boundary with a dedicated check method.

### C4. Class named `HKDF` actually uses HMAC-SHA512
- **Severity:** low (documentation / naming)
- **Scope:** android-only
- **Location:** `FC-AJDK/src/main/java/.../core/crypto/HKDF.java:12`
- **Problem:** The class is called `HKDF` but internally instantiates `Mac.getInstance("HmacSHA512")`. A reader auditing the crypto agility surface would reasonably read the name and infer HKDF-SHA256. Naming hides the actual construction.
- **Fix:** Rename to `HkdfSha512`, or parameterise over the digest and pass it in explicitly.

### C5. X25519 bypasses HKDF, derives keys via raw SHA-512
- **Severity:** medium
- **Scope:** android-only
- **Location:** `FC-AJDK/src/main/java/.../core/crypto/X25519.java:51-61`
- **Problem:** Computes `sha512(nonce || sharedSecret)` and takes bytes `[0..32)` as the symmetric key. Loses the domain separation and collision-resistance properties HKDF provides, and — importantly — is **incompatible** with the Ecc256K1 ECDH path, which uses HKDF-SHA512 with `info = "hkdf"`. A message encrypted by the secp256k1 code cannot be decrypted by the X25519 code even if they agreed on the same shared secret.
- **Fix:** Use HKDF-SHA512 with an explicit `info` string (e.g. `"fudp-x25519"`) matching the contextual convention of the secp256k1 path. Pick one derivation story and apply it everywhere.

### C3. BIP39 wordlist integrity not verified at load
- **Severity:** low (moot after mnemonic removal)
- **Scope:** android-only — mnemonic support is being dropped from Freer entirely.
- **Location:** `FC-AJDK/bip39-english.txt` + loader in `core/crypto/KeyTools.java`.
- **Problem:** Loader verifies word count (2048) but not the SHA-256 of the wordlist. A tampered wordlist would produce valid-looking but non-standard keys.
- **Fix (if kept):** Ship the expected SHA-256 as a constant and assert on load. **Planned action:** delete the wordlist and all mnemonic code paths as part of the mnemonic-removal refactor.

---

## Deprecated / stale APIs

### D1. `onBackPressed()` usage
- **Severity:** low
- **Scope:** android-only
- **Locations:** multiple activities under `app/src/main/java/com/fc/freer/home/` (comments already note this).
- **Problem:** `onBackPressed()` is deprecated on Android 13 (API 33). Target SDK is 34.
- **Fix:** Register an `OnBackPressedCallback` via `getOnBackPressedDispatcher().addCallback(this, cb)`.

### D2. Legacy `core/crypto/old/` package
- **Severity:** low
- **Scope:** android-only
- **Location:** `FC-AJDK/src/main/java/.../core/crypto/old/`
- **Problem:** Appears to be archived legacy. Still compiles into the APK.
- **Fix:** Delete if unused; otherwise mark `@Deprecated` and document the sunset plan.

---

## UX / i18n / code hygiene

### U1. Hardcoded user-visible strings
- **Severity:** low
- **Scope:** android-only
- **Locations (non-exhaustive):**
  - `KeyCardContainer` — literal `"Delete"`.
  - Likely more across `ui/` and feature packages; a full audit is a follow-up.
- **Fix:** Move to `res/values/strings.xml`; enforce via a lint rule (`HardcodedText`).

### U2. Outstanding `TODO` markers
- **Severity:** low
- **Scope:** android-only
- **Locations:**
  - `home/.../ProofActivity` — record functionality
  - `home/.../TokensActivity`, `home/.../MyTokenActivity` — hidden items UI
  - `manager/DataSyncManager` — debug logging left in
  - `manager/CashManager` — test unfinished
  - `tx/TxSender`
  - `ui/ApiCardContainer`
- **Fix:** Triage — either close out or move to an issue tracker.

---

## Architecture notes (not bugs, but called out)

### A1. APIP is being retired
- All code under `data/apipData/`, `ApipClient`, `ApipUrl`, and related `ClientGroup` strategies for APIP can be deleted once FUDP/FAPI fully covers the feature set. The Mac rewrite skips APIP entirely.

### A2. Mnemonic (BIP39) is being retired
- All BIP39 mnemonic code, the bundled `bip39-english.txt`, and `CreateKeyByPhraseActivity`'s BIP39 path can be removed. The replacement is direct Argon2id KDF from passphrase → 32-byte private key (not a BIP39 seed).

---

## Template for new entries

```
### <CATEGORY><NUM>. <One-line title>
- **Severity:** high | medium | low
- **Scope:** android-only | port | both
- **Location:** <file>:<line> (or file list)
- **Problem:** <what's wrong>
- **Fix:** <what to change>
```
