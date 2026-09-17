# Prompt: Port "Getting started" from Freer for Mac

## Goal

Replace Android's scattered newcomer prompts with a single **Getting started** checklist,
matching what Freer for Mac shipped in commit `1cf4b84` ("Getting started, and the CID and
DOCK/DISK carves it asks for"). The six steps are: back up the prikey, get a first FCH,
register a CID, set a DOCK and DISK, add the guide to contacts, join a square.

The Mac repo is at `/Users/liuchangyong/MacApp/FreerForMac`. Read the commit
(`git -C /Users/liuchangyong/MacApp/FreerForMac show 1cf4b84`) before writing code. The
commit message and doc comments explain *why* each rule exists, and those rules matter more
than the SwiftUI layout. This is a port of behaviour. Follow this repo's `CLAUDE.md`,
`AGENTS.md` and Java conventions; don't imitate the Swift structure.

## Mac sources to read

| Mac file (under `Packages/FCDomain/Sources/FCDomain/` unless noted) | What it holds |
|---|---|
| `Configure/Onboarding.swift` | `FeipCdd`, `OnboardingStep`, `OnboardingStatus`, `OnboardingFacts`, `Onboarding`: the whole decision, as a pure function |
| `Directory/IdentityFeip.swift` | `CidFeip` (register + `preview`), `HomeFeip` (register, `merged`, `declares`) |
| `Directory/PendingIdentityCarves.swift` | Pending CID/master/home carves, `isLanded(on:)`, `reconcile` |
| `Configure/Setting.swift` (diff) | `onboardingSkipped`, `onboardingStarted` persisted flags |
| `Configure/ActiveSession.swift` (diff) | `previewCid`, `carveCidOnChain`, `carveHomeOnChain`, master write-once check, reconcile on refresh |
| `Im/GroupService.swift` (diff) | `popularSquares` query |
| `Tests/FCDomainTests/Configure/OnboardingTests.swift`, `Directory/IdentityFeipTests.swift` | Test cases to port as JUnit tests |
| `Sources/FreerForMac/Views/Panes/GettingStartedCard.swift` | UI, step titles, explanations, status lines |
| `Sources/FreerForMac/Views/Panes/IdentitySettingsSection.swift` | Settings › Identity (CID, master, DOCK/DISK) |
| `Sources/FreerForMac/Views/Panes/NewChatSheet.swift` (diff) | Popular squares; sheet closes after a broadcast |

## The model (port it exactly)

**Every tick is read from state, never clicked.** The prikey backup is the only exception,
because nothing else can know. The chain, contacts and squares decide everything else, so
work done on another device (or before the checklist existed) shows as done.

Steps in order, and when each is **done**:

1. `backupPrikey`: the setting flag says so. Required.
2. `firstFch`: the chain record has `balance > 0` **or** a non-empty `guide`. Required.
3. `registerCid`: the chain record has a non-empty `cid`. Required, carve.
4. `setHome`: the chain `home` declares both DOCK and DISK. Match keys by **prefix,
   case-insensitive**, with a non-blank value, since other clients may write a bare `DOCK`. Required, carve.
5. `addGuide`: the guide FID is in local contacts. Skippable. Omit the step entirely when the
   FID is funded but has no guide on record.
6. `joinSquare`: the user is a member of at least one square. Skippable, carve (join or
   create both count).

Statuses: `done`, `skipped`, `open`, `waiting(step)`, `waiting(coinDays have/need/days)`,
`pending(txid)`, `stalled(txid)`, `unknown`. For a carve step, check in this order:
chain unknown → `unknown`; done on chain → `done`; a recorded pending carve → `pending`
(or `stalled` once it's older than one day); not funded → `waiting(firstFch)`; CDD not met →
`waiting(coinDays)`; otherwise `open`.

- **Done only when the chain shows it**, never on broadcast. A carve can be dropped, or
  confirmed and ignored.
- `current` = the first item that is not settled, not unknown, and not pending. That's the row that expands.
- `shouldShow(started)`: show if any *required* step is still open (as far as we know);
  otherwise show only if `started && !isComplete`. Set `onboardingStarted = true` the first
  time the card is shown with a required step open. An identity that was already set up must
  never see the card just for the two optional steps.
- Watch-only identities (can't sign) never see the card.
- Use only chain data confirmed **in this session**. A cached record from an earlier launch
  must not tick or untick steps. Also don't blank the card while a refresh is in flight.

## Protocol rules that cost fees if missed

1. **FEIP0 §9 CDD.** From height `Feip.CDD_CHECK_HEIGHT` (4,000,000), a carve destroying less
   than `Feip.CD_REQUIRED` is confirmed, paid for and ignored. Use those existing constants.
   Don't add new ones. An unknown best height counts as "required". A carve step whose CDD
   isn't met shows "Your coins are still aging: *have* of *need* CD, about *N* days to go"
   (N = ceil((need − have) / balance in coins), min 1; omit the days when balance is 0)
   **instead of** a carve button. `TxSender` already resolves `Feip.getRequiredCd`. Keep that
   as the last guard.
2. **FEIP9 register replaces the whole home map.** Merge DOCK/DISK over the map the **chain
   holds now**, fetched fresh, not `liveKeyInfo.getHome()`. If nothing would change, refuse
   ("already on the chain") instead of paying.
   ⚠ `ServerSetupManager.register` and `DiskHomeManager` currently merge over the cached
   `liveKeyInfo.getHome()`. Fix that as part of this port.
3. **FEIP6 master is write-once.** Before carving a master, fetch the main FID's chain record.
   If `master` is non-empty, refuse and name the existing master. Don't use the local
   `KeyInfo.master`: it's written on broadcast, so it can name a master whose carve never
   landed. ⚠ No such check was found in `SetMasterActivity`. Add it, and fix any copy that
   says a later carve can change the master.
4. **CID preview must match the parser (FEIP3).** Name rule: non-empty, no whitespace, `@`,
   `#` or `/`. Candidate = `name_` + the FID's last 4 chars. For each length from 4 up to the full FID:
   if the candidate is in the FID's **own** `usedCids`, it's a *reactivation* (never counts
   toward the limit); if another FID has ever used it (`getFidByUsedCid` returns a
   different FID), lengthen the suffix; otherwise it's *new*, or *limit reached* if own
   `usedCids` already has 4. If every length is taken, it's *unavailable*. Read `usedCids` fresh from
   the chain.
   ⚠ `SetCidActivity.checkCidAvailability` treats *any* owner, including the FID itself, as
   taken, and runs the 4-CID check before the collision loop. Check both against the rule
   above.
5. **Don't offer a paid carve twice.** Record each broadcast CID, master and home carve
   (fid, kind, carved value, txid, broadcast time; one record per fid+kind, newest replaces).
   On every refresh of the live FID's record, clear the ones that landed:
   - CID: the chain `cid` starts with `name_`, and the rest is ≥4 chars and a suffix of the FID
     (not a bare prefix test).
   - master: any non-empty master.
   - home: every carved key/value is in the chain map.
   After one day without landing, the step becomes `stalled`: the actions reopen, and the txid
   is shown so the user can check it. Squares use whatever Android already
   records for pending joins/creates. `ServerSetupState` / `DiskHomeManager.markRegistrationPending` /
   `ImManager.onRegistrationTxSent` already cover part of this for DOCK/DISK. Unify with
   them rather than adding a second, disagreeing record.

## What Android already has

| Need | Existing piece | Gap |
|---|---|---|
| Backup flag | `Setting.KEY_PRIKEY_BACKED_UP` in the state map; `BackupPrikeyDialog` | `HomeActivity.checkPrikeyBackup` shows a modal on every launch. The checklist replaces that nag. |
| First FCH | `TopupPromptDialog`, `NewcomerBoard` ("Help beginners" in settings) | Step action should open these |
| CID | `SetCidActivity`, `FapiClient.getFidByUsedCid`, `CidOpData` | Preview rule (above), pending record |
| DOCK/DISK | `ServerSetupManager`, `ServerSetupActivity`, `ChannelSetupDialog`, `DiskHomeManager`, `HomeOpData` | Fresh-chain merge, unchanged refusal. Decide whether `ChannelSetupDialog`'s prompt stays once the checklist covers it. |
| Master | `SetMasterActivity` | Write-once chain check. Master is **not** a checklist step. |
| Chain record | `Freer` has `balance`, `cd`, `guide`, `home`, `master`, `usedCids`; `FapiClient.getBestHeight()` caches the reply's height | Make sure the live-FID refresh keeps all of them together |
| Join square | `JoinSquareActivity` (search only; finishes on broadcast) | Add a "popular squares" list shown before any search |
| CDD | `Feip.CD_REQUIRED`, `Feip.CDD_CHECK_HEIGHT`, `Feip.getRequiredCd` | Forecast UI |

**Popular squares** = FAPI `base.search` on entity `square`, sorted `memberNum` desc, then
`lastHeight` desc, then `id` desc, size 20. Sort by **members, not `tCdd`**: `tCdd` only ever grows,
and it can be inflated by name wars or one large join. Clearing the search box returns to the
popular list. Hide the join action for squares with a join broadcast in the last day.

## UI

- The card goes at the top of the home screen: a "Getting started" title, "*n* of *m* done", a
  progress bar, and one row per step. Only the current step (or the one the user tapped) is
  expanded with its explanation and actions. Skippable steps have a **Skip** action (persist
  the step name in `onboardingSkipped`, comma-joined, ignoring unknown names on read).
- Actions: backup → `BackupPrikeyDialog`; first FCH → Help beginners / top-up; CID →
  `SetCidActivity`; DOCK/DISK → server setup; guide → create contact prefilled with the
  guide FID (plus a way to view the guide's details); join → `JoinSquareActivity`.
- Status lines: "Skipped", "Checking the chain…", "Waiting for the chain" + txid, "Not on
  the chain after a day" + txid, "After “*step title*”", and the coin-age line above.
- Copy titles and explanations from `GettingStartedCard.swift` (`title`, `explanation`),
  adapted for a phone ("on this Mac" → "on this phone"). Add `values-zh` translations too.

## House rules (from the Mac project)

- User-facing text says **prikey, pubkey, symkey**, never "private/public/symmetric key".
- FIDs, txids and status messages: a single tap copies to the clipboard with a brief confirmation.
- Shortened IDs keep head **and** tail (`FEk4…vkUV`), never `prefix + "…"`.
- FCH P2PKH spends need BCH-Schnorr signatures. Check that any new carve path goes through
  the existing `TxSender`, which already handles this.

## Done when

- The pure checklist decision lives outside any Activity and has JUnit tests ported from
  `OnboardingTests.swift` and `IdentityFeipTests.swift`: CDD waits and forecasts, pending vs
  stalled, `shouldShow` for pre-existing identities, CID preview (new / reactivate / limit /
  collision / unavailable), home merge and prefix `declares`, `isLanded` shapes.
- The master carve refuses when the chain already has a master, and the home carve merges over a
  fresh chain map.
- `./gradlew test` and `./gradlew assembleDebug` pass. The card is checked on a device with a
  fresh identity and with an already-set-up one (which must not see the card).
