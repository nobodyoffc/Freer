# Voice calls: testing on real phones (Phase 3, milestone 4)

How to make a real 1:1 call between two Android phones with the debug build.
It goes through the whole stack: FIMP signalling, the CALL relay, sealed
audio and attestations.

## What you need

- **Two phones, each with its own Freer identity.** Log in and set up a
  FID on each, so that each can send the other a chat message. A call rings
  over the same channels as chat (FUDP direct, ROAD and DOCK). If a chat
  message gets through, so will a ring.
- **Make each a contact of the other.** Otherwise an INVITE is held as a
  message request instead of ringing (VOICE_SPEC §3.2). Sending one chat
  message from each side is enough.
- **A CALL relay.** Until one is registered on chain, run the test relay on
  your server (UDP 19950 open):
  ```bash
  mvn -f FC-JDK/pom.xml test -Dtest=CallRelayServer -Dsurefire.failIfNoSpecifiedTests=false
  ```
  Wait for `[CallRelayServer] CALL relay on UDP 19950 as F…`. It runs for
  4 hours (`-Dcall.minutes=` to change).

## Point both phones at the relay

On each phone, open **Tools → Voice test** (debug builds). In **Call relay
for real calls**, enter `fudp://<server-ip>:19950`, then tap another field so
it is saved. Or, from a Mac with the phone plugged in:
```bash
adb shell am start -n com.fc.freer/.call.spike.VoiceSpikeActivity \
    --es callRelay fudp://<server-ip>:19950
```

## Make a call

1. **Phone A:** open the chat with B, then **⋯ → Voice call**. Allow the
   microphone and notifications. The call screen says *Calling…*.
2. **Phone B** rings: the call screen opens, or a full-screen notification
   appears with the default ringtone. Tap **Answer**.
3. **Talk.** The screen shows the duration, *End-to-end encrypted*, and
   *Relayed via <server> · RTT*.
4. **Hang up** on either side. Both chats show *Outgoing call, m:ss* /
   *Incoming call, m:ss*.

Also try:
- **Decline** on B: A sees *Declined*, and both chats record it.
- **Let it ring for 45 s:** A sees *No answer*, B's chat says *Missed call*.
- **Cancel** from A before B answers: B's chat says *Missed call*.
- **A second phone logged in as B:** it rings too, and stops with *Answered
  on another device* when the first answers.
- **Lock B's screen, then call:** it should wake with the call screen.

## Report

For each try: what you heard, and the call screen's text. If something fails,
also send `adb logcat -d | grep -E "CallSession|CallManager|ImManager|P2pHandler"`
from each phone, and the relay's output.
