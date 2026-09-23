# Voice spike: running the Phase 2 gate

How to run the tests of VOICE_SPEC §14, Phase 2, on real phones. The spike
is a debug-only screen, **Tools → Voice test**, plus a throwaway relay in
FC-JDK. Nothing in it is product code except the engine under
`app/.../call/engine/`, which Phase 3 keeps.

## What is built

| Part | Where |
|---|---|
| libopus 1.5.2 (vendored, no DNN models), JNI | `app/src/main/cpp/` |
| Opus wrapper, capture, playout and mixer, jitter buffer | `app/.../call/engine/` |
| Test screen, its FUDP node | `app/.../call/spike/` |
| Forward-everything relay | FC-JDK `src/test/java/fudp/VoiceSpikeRelay.java` |
| Jitter buffer tests | `app/src/test/.../call/engine/JitterBufferTest.java` |

Frames are the §5 MediaFrame header with `kind = 0x7E` and Opus in the
clear: FUDP's hop encryption only, no E2EE yet. Settings follow §9.1: 48 kHz
mono, VOIP, VBR, in-band FEC, DTX on. Capture uses `VOICE_COMMUNICATION`
with the platform AEC/NS/AGC; the screen shows which ones the phone applied.

## Paths

- **Direct (same Wi-Fi):** phone A picks *Wait for a call* and shows its
  address. Phone B picks *Call a waiting phone* and enters that IP. Both use
  the same room code; the keys come from it. UDP 19870.
- **Relay:** start the relay on a server with UDP 19900 open:
  ```bash
  mvn -f FC-JDK/pom.xml test -Dtest=VoiceSpikeRelay -Dsurefire.failIfNoSpecifiedTests=false
  ```
  Each phone picks *Join the voice relay* and enters the server's address.
  Any number can join; everyone hears everyone else, mixed.

Use the same frame size (20 or 40 ms) on every phone. The screen warns if not.

## Scripted start

The screen takes its settings as extras. In debug builds it is exported, but
only the shell can start it (it requires the DUMP permission):
```bash
adb shell am start -n com.fc.freer/.call.spike.VoiceSpikeActivity \
    --es mode relay --es host 203.0.113.7 --ei frameMs 40 --ez dtx true --ez autostart true
```
`mode` is `host`, `call` or `relay`. The other extras are `room`, `kbps`,
`fecLoss` (the loss FEC is sized for), `simLoss` (the percentage of received
frames to drop) and `speaker`. Stats go to logcat every 5 s:
`adb logcat -s VoiceSpikeActivity`.

## The gate

### 1. Delay ≤ 300 ms relayed (mouth to ear)

Use the relay, and put it on a server the phones reach over the internet,
not the LAN.

1. **Wire it up.** Feed one sound source to two places: phone A's
   microphone (through a headset-jack or USB-C adapter with a mic input,
   i.e. a loopback cable), and channel 1 of a recorder (a PC's line-in, or a
   second audio interface). Connect phone B's headphone output to the
   recorder's channel 2.
2. **Record.** Play sharp clicks, for example a metronome at 1 per second.
   Record both channels for 30 s.
3. **Measure.** Mouth-to-ear delay is the time from each click on channel 1
   to the same click on channel 2. Take the median and the worst.
4. **Compare with the screens.** Phone B's `network + jitter buffer +
   speaker path` line, plus one frame, accounts for all but phone A's
   microphone path, which the phones do not report. The difference from
   the measured delay is roughly that microphone path.

Without a cable: record with a third device while saying a sharp "ta" into
phone A every few seconds. Phone B must be **muted** and in **another room or
on earphones**; if its speaker can reach phone A's microphone, the call
feeds back on itself (A's echo canceller only removes A's own playback). The
recording shows each "ta" twice: directly, with energy above 8 kHz, and from
phone B, with none. The gap is the mouth-to-ear delay.

### 2. Intelligible at 5% loss

On the relay host, add loss and jitter to what the relay sends:
```bash
sudo tc qdisc add dev eth0 root netem loss 5% delay 30ms 30ms distribution normal
# afterwards: sudo tc qdisc del dev eth0 root
```
(Use the host's real interface name from `ip route`.) Talk for a minute
each way. The screen should show loss near 5%, most of it as `fec`
(recovered), not `concealed`. Judge it by listening: can you follow
ordinary speech without asking for repeats? The in-app *Drop % received*
field tests the same thing without netem, but without the jitter.

### 3. No audible echo on speakerphone, on three phone models

Two phones in different rooms, direct or relayed. On each of the three
models under test, turn on *Speaker* at normal volume. The far end talks;
listen at the far end for their own voice coming back. Also note the
`effects:` line: a phone without `AEC` is the likeliest to fail.

### If the gate fails

Stop and reconsider WebRTC's audio stack, keeping FUDP as the transport
(§14). Note which test failed, on which phones, and the screen's stats.

## What the stats mean

```
RELAY  42s  RTT 150 ms
send  ssrc 5c893f6a  level -38 dBov  28.4 kbps
      frames 2050  dtx-skipped 0  dropped 0
      effects: AEC NS
device  mic queue 0 ms (dropped 0)   app->speaker 166 ms
recv  datagrams 2011  sim-dropped 0  track buffer 40 ms (underruns 0)
from  ssrc b7136522  level -41 dBov
      loss 4.9%  fec 47  concealed 3  late 1
      buffer 60 ms / target 60  stretched 12  skipped 9 (forced 2)  dtx 0
      network ~75 + jitter buffer 60 + speaker path 166 = 301 ms (+ sender's mic path + 20 ms frame)
```

- **mic queue:** captured audio waiting to be encoded that never drains.
  Anything over one frame is dropped (`dropped`, in ms). It is *not* the
  microphone's whole path: the phones tested so far report capture times
  at read time, so the mic and AEC/NS part cannot be measured from inside.
- **app->speaker:** from writing a frame to the device playing it: our
  AudioTrack buffer (`track buffer`) plus the platform's mixer, DSP and
  driver.
- **loss:** frames that never arrived, as a share of those that should have.
  **fec** are recovered from the next frame's FEC data. **concealed** are
  covered by Opus PLC.
- **late:** frames that arrived after their turn, and were discarded.
- **buffer / target:** the jitter buffer depth now, and the target derived
  from the last 2 s of jitter (40–300 ms).
- **stretched:** concealment played while waiting for a late frame, which
  grows the delay. **skipped:** frames dropped to shrink it again. Quiet
  frames go first; **forced** ones were dropped mid-speech, after a second
  over target with no quiet frame to take.
- **dtx:** frames the sender did not send because it was silent. They are
  not counted as loss.
- **network + jitter buffer + speaker path:** this phone's share of the
  mouth-to-ear delay. Add the other phone's microphone path (unmeasured,
  typically 50–150 ms with AEC/NS) and one frame for the total.

On emulators, `skipped` climbs steadily, because headless emulator audio
does not run at a true 48 kHz. On phones it should stay small.
