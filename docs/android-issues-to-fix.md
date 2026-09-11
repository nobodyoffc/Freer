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

### C6. Hash tool's SHA3 hashes the hex *string*, not the input bytes
- **Severity:** low
- **Scope:** both (results differ between Android and the Mac port)
- **Location:** `app/src/main/java/com/fc/freer/tools/HashActivity.java:396` (`applyHashAlgorithm`, `radioSha3` branch)
- **Problem:** Every other algorithm hashes `inputBytes` directly, but the SHA3 branch calls `Hash.sha3String(Hex.toHex(inputBytes))` — it hex-encodes the input and hashes the UTF-8 bytes of that hex string. So "abc" is hashed as the 6 ASCII chars `616263`, doubling the input and producing a digest that matches no standard Keccak-256 of the input. (Also note `Hash.sha3*` is Keccak-256, not NIST SHA3-256 — the label "SHA3" is misleading either way.)
- **Fix:** Use `Hash.sha3(inputBytes)` and hex the result, like the other branches. The Mac port (ToolsView → "Keccak256") already hashes the raw bytes, so Android should converge on that behaviour for cross-checking to work.

### C7. A mail is encrypted with a different algorithm depending on which button you press
- **Severity:** medium
- **Scope:** both (found while porting Mail to the Mac, 2026-08-14)
- **Location:** `FC-AJDK/.../data/feipData/Mail.java` `encryptContent` vs `app/.../mail/CreateMailActivity.java:705` `encryptMailContent`
- **Problem:** The two encrypt paths for the same field disagree. `Mail.encryptContent` — used by `sendMail()`, i.e. every mail that goes **on chain** — asks for `FC_EccK1AesCbc256_No1_NrC7`. `CreateMailActivity.encryptMailContent` — used by the local-save paths — asks for `FC_EccK1AesGcm256_No1_NrC7`. So the copy in your outbox and the copy on the chain are sealed under different algorithms, and the *carved* one is the unauthenticated CBC of the two. CBC's only integrity check here is the 4-byte `sum`, which `Decryptor` does verify, but a 4-byte tag is 32 bits of protection on a payload that anyone can rewrite in a competing carve.
- **Fix:** Use `FC_EccK1AesGcm256_No1_NrC7` in `Mail.encryptContent` too. Reading stays backward-compatible either way — `Decryptor` dispatches on the envelope's `alg` — so this is a write-side change only. The Mac port reads both and writes GCM.

### C8. `Encryptor` produces a cipher its own `Decryptor` rejects for empty input
- **Severity:** low
- **Scope:** android-only (no product path sends an empty payload)
- **Location:** `FC-AJDK/.../core/crypto/Encryptor.java` / `Decryptor.java`, Asy paths
- **Problem:** `encryptByAsyTwoWay(new byte[0], …)` returns success, but feeding the resulting envelope back to `Decryptor.decryptTry` fails with code 1029 ("Failed to decrypt"). Round-tripping any non-empty payload works. Found while generating AsyTwoWay golden vectors: the zero-length case had to be dropped because the reference implementation has no correct answer for it.
- **Fix:** Either reject empty input at encrypt time with a clear code, or fix the decrypt path to return an empty `data` array. Silently producing an unreadable ciphertext is the worst of the three.

### C9. The mail notice fee mixes coins and satoshis in one comparison
- **Severity:** high — the failure mode is overpaying by orders of magnitude
- **Scope:** both (found while porting Mail to the Mac, 2026-08-14)
- **Location:** `app/.../mail/CreateMailActivity.java:612-617`, with `MailManager.calculateMailFee` and `MailActivity.java:1533`
- **Problem:** `payNoticeFee` is a **double in coins** — `calculateMailFee` returns `FchUtils.satoshiToCoin(DEFAULT_MAIL_FEE_SATOSHI)` (0.0001) or `Double.parseDouble(freer.getNoticeFee())`, and it is handed to `carveFeipWithRecipient(…, Double amount, …)` → `new Cash(recipient, amount)`. But `gotNoticeFeeLong` is a **`Long`** read from `mail.getNoticeFee()`, and the reply path does:
  ```java
  if (… && gotNoticeFeeLong > payNoticeFee) payNoticeFee = gotNoticeFeeLong;
  ```
  Both the comparison and the assignment cross units. If `Mail.noticeFee` is satoshis (which is what the chain indexes a paid output as), then a routine 10 000-satoshi fee compares as `10000 > 0.0001` — always true — and the reply pays **10 000 F**. If it is coins, every ordinary fee truncates to `0` and the pay-back rule silently never fires. Neither reading is the intended behaviour.
- **Fix:** Pick one unit for the whole path — satoshis is the natural one, since that is what the output carries — and convert exactly once, where `Freer.noticeFee` (a coin-denominated string) is parsed. The Mac port does this in `NoticeFee`, which is satoshis end to end and converts only in `satoshis(coinString:)`.
- **Related, same area:** Android applies the max-paying cap *before* the pay-back bump, so a reply can pay any amount regardless of the user's configured limit — a correspondent can step around it by attaching a large notice fee to their mail. The Mac port applies the cap last.

### C10. The mail size check measures the wrong thing, so oversize mail fails at broadcast
- **Severity:** medium
- **Scope:** both
- **Location:** `app/.../mail/CreateMailActivity.java:562` (`content.length() > Constants.MaxOpReturnSize`)
- **Problem:** The check compares the **plaintext body** against the 4 096-byte OP_RETURN limit, but what goes into the OP_RETURN is the body *encrypted, base64-encoded, wrapped in a CryptoDataStr envelope with two 33-byte pubkeys, and wrapped again in the FEIP envelope*. Base64 alone costs a third. The real ceiling is **2 786 bytes** of body; anything between that and 4 096 passes the check, gets encrypted, has a notice fee decided, gets signed — and is rejected by the network. Also note `String.length()` counts UTF-16 units, not bytes, so a body of CJK text overflows a good deal earlier still.
- **Fix:** Check the assembled carve, not the body, and drive the compose screen's character counter from the real budget. The Mac port exposes `MailFeip.maxBodyBytes` (measured against the actual encoder, currently 2 786) and `sendCarve` throws on the assembled payload before anything is signed.

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

### C11. The first entry of the message-retry backoff is dead code
- **Severity:** low — the effect is a slower first retry, not a lost message
- **Scope:** android-only (found while porting IM delivery to the Mac, 2026-08-14)
- **Location:** `app/.../im/MessageQueue.java:243-255`
- **Problem:** `RETRY_DELAYS_MS` is `{5s, 15s, 1m, 5m, 15m}`, but the index is read *after* the counter is incremented:
  ```java
  int retryCount = qm.getRetryCount() + 1;          // 0 → 1 on the first failure
  long delay = RETRY_DELAYS_MS[Math.min(retryCount, RETRY_DELAYS_MS.length - 1)];
  ```
  So the first retry waits `RETRY_DELAYS_MS[1]` = 15 s and index 0 is never read at all. The array reads as a five-step schedule and behaves as a four-step one, with the quick first retry — the one that rides out a momentary loss of signal — missing.
- **Fix:** Index with `retryCount - 1` (or read the delay before incrementing). The Mac port uses `retryDelaysMs[min(attempts - 1, count - 1)]` and its `testTransientFailureWalksTheBackoffSchedule` asserts all five steps.

---

### C12. A room invitation can take over a room you are already in
- **Severity:** medium — needs the user to accept, but the prompt looks entirely legitimate
- **Scope:** both (found while porting Rooms to the Mac, 2026-08-14)
- **Location:** `app/.../im/RoomHandler.java:554` (`acceptRoomInvite`), reached via `ImManager.java:1113` (`createRoomInvitePendingIssue`)
- **Problem:** A `RoomInfo` payload carries its own `owner` field, written by whoever sent it. `ImManager` computes `isFromOwner` by comparing the *sender* to that self-declared field, so anyone who knows a room id can set `owner` to themselves and satisfy it. `handleRoomInfoShare` then rejects the message for an existing room (sender is not a member) and returns false — but the `else if (isFromOwner)` branch still fires and raises a **ROOM_INVITE pending issue** for a room the user is already in. If the user accepts, `acceptRoomInvite` does:
  ```java
  Room newRoom = roomInfo.toRoom();   // owner = attacker, members = attacker's list
  saveRoom(newRoom);                  // overwrites the real room record
  symkeyStore.receiveSharedSymkey(id, version, roomInfo.getSymkey(), /* fromOwner */ true);
  ```
  so one tap replaces the room's owner, its member list, and — because `fromOwner` is hardcoded to `roomInfo.getOwner() != null` — its stored symkey for that version. The invite UI shows a plausible room name, and a room id is known to every current and *former* member.
- **Fix:** Two changes, either of which closes it. (1) Do not raise an invite issue for a room id already in the DB — an existing room's updates go through `handleRoomInfoShare`'s member check and nowhere else. (2) In `acceptRoomInvite`, refuse when a room with that id exists under a different owner, and pass `allowOverwrite = false` to `receiveSharedSymkey` (on a genuine first join there is nothing to overwrite, so it costs nothing). The Mac port does both: `RoomService.acceptInvite` throws `invitationOwnerMismatch` and stores the shared key with `allowOverwrite: false`.

---

### C14. The Address converter derives a hash160 without checking the address checksum

- **Severity:** medium — a mistyped address converts silently into addresses for a key nobody holds
- **Scope:** android-only (found while porting the Converter to the Mac, 2026-08-17)
- **Location:** `FC-AJDK/.../core/crypto/KeyTools.java:368` (`addrToHash160`), reached from `app/.../convert/AddressConverterActivity.java:141`
- **Problem:** `addrToHash160` does a plain Base58 decode and slices bytes 1…21, discarding the trailing 4-byte checksum without ever verifying it:
  ```java
  byte[] addrBytes = Base58.decode(addr);
  byte[] hash160Bytes = new byte[20];
  System.arraycopy(addrBytes, 1, hash160Bytes, 0, 20);
  ```
  The checksum is the only thing standing between a typo and a different, valid-looking hash160. `AddressConverterActivity.convert()` feeds the result straight to `hash160ToAddresses`, so a single wrong character produces a complete, confident table of FCH/BTC/BCH/LTC/DOGE addresses for a key that does not exist. The user's only clue is that the output does not match what they expected — and the whole reason to use a converter is not knowing what to expect. Anything sent to one of those addresses is unspendable by anyone.
- **Fix:** Decode with `Base58.decodeChecked` (already present in the same class and used by `getPubkey33`) and surface the failure. The Mac port's `ChainAddresses.hash160(fromAddress:)` goes through `Base58Check`, and `testHash160FromAddressRejectsBadChecksum` flips one character of a valid FID and asserts it throws.

---

### C15. `FcDate` and `TimeConvertActivity` disagree about the genesis epoch by two seconds

- **Severity:** low — a one-block error, and only for instants near a block boundary
- **Scope:** android-only (found while porting the Converter to the Mac, 2026-08-17)
- **Location:** `FC-AJDK/.../utils/FcDate.java:33` vs `app/.../convert/TimeConvertActivity.java:18`
- **Problem:** The genesis timestamp is written twice with two values. `FcDate.GENESIS_UNIX_SECONDS = 1577836802L` (the real genesis block time, 2020-01-01 00:00:02 UTC); `TimeConvertActivity.FCH_GENESIS_TIMESTAMP = 1577836800000L` ms (00:00:00). The activity computes `height` from its own constant and then renders that height with `FcDate.fromHeight`, so the two halves of one screen are keyed to epochs two seconds apart. Inside a two-second window at each block boundary the displayed height and the displayed FcDate refer to different blocks, and `convertFromFcDate` → height → timestamp does not return the timestamp it started from.
- **Fix:** Delete the activity's constant and use `FcDate.GENESIS_UNIX_SECONDS` (× 1000) — or better, let `FcDate` own both directions so the activity does no epoch arithmetic at all. The Mac port keeps the single `FcDate.genesisUnixSeconds` and routes every conversion through it; `testFcDateRoundTripsThroughUnixSeconds` pins the round trip.

---

### C16. The String converter's "UTF-8" line does not decode back to the bytes beside it

- **Severity:** low — misleading output, no data loss
- **Scope:** android-only (found while porting the Converter to the Mac, 2026-08-17)
- **Location:** `app/.../convert/StringConvertActivity.java:256`
- **Problem:** `displayResults` renders the UTF-8 row unconditionally with `new String(bytes)`. For arbitrary binary — which is the normal case, since the input is usually a hex key or a Base58 payload — the platform decoder substitutes U+FFFD for every unmappable sequence. The row is presented as a peer of the Hex/Base58/Base64/Base32 rows and is click-to-copy like them, but copying it yields replacement characters that decode to something else entirely. It reads as "your bytes, as text" and is not.
- **Fix:** Render the UTF-8 row only when the bytes are valid UTF-8 (decode strictly and omit the row on failure). The Mac port's `StringCodec.renderAll` appends the UTF-8 entry only when `String(data:encoding:.utf8)` succeeds; `testStringCodecOmitsUtf8ForNonTextBytes` covers it.

---

### C17. News search paging sends a `[time, id]` cursor no matter which field the results are sorted by

- **Severity:** medium — pagination silently breaks on four of the five offered sorts
- **Scope:** android-only (found while porting News to the Mac, 2026-08-18)
- **Location:** `app/.../manager/FcObjectManager.java:191-217` (`buildSearchFcdsl` + `addSortingToFcdsl`), consumed by `NewsActivity.fetchOlderNewsFromAPI` / `fetchNewerNewsFromAPI`
- **Problem:** `addSortingToFcdsl` sorts by whatever the user picked in the Sort spinner (`doer` / `objectType` / `act` / `time` / `objectName`, plus an `id` tiebreaker), but the cursor `buildSearchFcdsl` appends is unconditionally `Arrays.asList(String.valueOf(referenceNews.getTime()), referenceNews.getId())`. `search_after` compares its values positionally against the sort keys actually in play, so as soon as the sort is anything but Time the first value is a timestamp being compared against a doer FID or a protocol serial. The user sees More return the wrong page, a repeat of the page they have, or nothing. Only the default Time sort is correct — which is why this survives casual testing.
- **Fix:** page from the server's own cursor (`fapiClient.getLastResponse().getLast()`), which the response already carries and which always matches the sort in use; failing that, build the cursor from the same fields `addSortingToFcdsl` named. The Mac port's `NewsService.search(...)` takes `after:` straight from `Page.last`, and only the fixed `time, id` browse walk rebuilds a cursor from a row.

---

### C18. The News list renders a selection model and a context action, and neither is wired to anything

- **Severity:** low — dead controls, no data loss
- **Scope:** android-only (found while porting News to the Mac, 2026-08-18)
- **Location:** `app/.../home/NewsActivity.java:295` (`new NewsCardContainer(this, newsListContainer, ChooseMode.CHOOSE_MULTI)`), `app/.../utils/NewsCardContainer.java`
- **Problem:** the container is constructed `CHOOSE_MULTI`, so every card inflates `news_checkbox` and the user can tick rows — but `NewsActivity` never calls `getSelectedNews()`, `removeSelectedNews()` or `selectAll(...)`, so a selection can only ever be discarded. The same holds for the long-press menu: `NewsCardContainer`'s default menu item is "Add doer to list", and `NewsActivity` never calls `setOnMenuItemClickListener`, so choosing it does nothing.
- **Fix:** either wire the selection and the menu to real actions, or construct with `ChooseMode.WITHOUT_CHOOSE` and drop the menu until there is one. The Mac port ships the doer action for real (a row context menu that opens the contact editor prefilled with the doer's FID) and has no checkboxes, since nothing there is multi-selectable.

---

### C19. A dismissed team member is never told, and keeps a live-looking team

- **Severity:** medium — the thread stays open and sendable for somebody the chain has removed
- **Scope:** both (found while porting team governance to the Mac, 2026-09-11; fixed in the port)
- **Location:** `app/.../im/TeamSyncManager.java:62-65` and `TeamHandler.refreshUpdatedTeams` (`terms members = liveFid`)
- **Problem:** both syncs ask only for teams whose `members` contains us. A `dismiss` moves the FID from `members` to `exMembers`, so the team is never returned to that member again: `leftGroup` is never set, the DOCK stays registered, and the composer keeps offering to send. Leaving by your own carve hides this, because the activity flags the conversation when it broadcasts — being dismissed has no such moment.
- **Fix:** query `members` **or** `exMembers` (one `terms` clause with both fields; the server ORs them). The Mac's `GroupService.fetchTeams` does this, and its existing `belongs = isMember && isActive` rule then flags the thread.

---

### C20. The team chat menu offers "Leave team" to the owner

- **Severity:** low — a paid carve that does nothing
- **Scope:** android-only (found while porting team governance to the Mac, 2026-09-11)
- **Location:** `app/.../im/ChatActivity.java` `showTeamChatMenu` (`menu_leave_team` is never hidden), `res/layout/popup_team_chat_menu.xml`
- **Problem:** `OrganizationParser` skips the owner in a `leave` op, so an owner who leaves from the chat menu pays the fee and stays owner. `TeamActivity.launchLeaveTeams` already routes owners to disband; the chat menu does not.
- **Fix:** hide Leave for the owner and show Disband there instead. The Mac's team menu does this.

---

### C21. Take-over quotes the cached consensus id, not the chain's

- **Severity:** medium — a stale cache turns a take-over into a rejected, paid carve
- **Scope:** android-only (found while porting team governance to the Mac, 2026-09-11)
- **Location:** `app/.../im/TeamTxHelper.java:180-201` (`resolveConsensusId`)
- **Problem:** the local team is consulted first and the chain only if there is none. The parser rejects a `take over` (and a `join`) whose `consensusId` differs from the team's current one, so an owner who changed the consensus after this device cached the team makes the take-over fail after the fee is spent. The comment on `sendAgreeConsensusTx` already says the id "must be the freshly-synced consensus" — the other two paths do not follow it.
- **Fix:** read the team from the chain at the moment of the carve and quote its id; refuse before signing when the signer is not the transferee (or, for a join, not in `invitees`). The Mac's `carveTeamTakeOverOnChain` and `TeamGovernance.joinRefusal` do both.

---

### C22. A square left from another device stays joined here

- **Severity:** medium — the thread stays open, the DOCK stays polled, and the composer keeps offering to send
- **Scope:** both (found while porting the square list to the Mac, 2026-09-11; fixed in the port)
- **Location:** `app/.../im/SquareSyncManager.java:63-66` and `SquareHandler.refreshUpdatedSquares` (`terms members = liveFid`)
- **Problem:** the sync only asks for squares whose `members` contains us, so a square the user left on another device — or one deleted when its last member left — is never returned again and its conversation is never marked left. The periodic full scan asks the same question and has the same blind spot. A square has no `exMembers` field, so the team fix (C19) does not apply.
- **Fix:** after the member query, read back by id every cached square that still lists us and was not returned; mark the conversation left when the record no longer lists us or no longer exists, and change nothing when that read fails. The Mac's `GroupService.syncSquares` does this.

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
