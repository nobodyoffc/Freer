// Does the echo canceller take echo out, and let the near talker through while
// both talk (the half-duplex fault it is for)? Far end: noise shaped like
// speech, played. Near end: the room's echo of it, 60 ms later and 6 dB
// quieter, with a little reverb, and after 5 s the near talker too.
//
// Judged on AEC3 alone: noise suppression, on in the app, treats this test's
// noise-like talker as noise, which a real voice is not. APM_NS=1 runs it with
// suppression on, for information.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

#include "api/audio/audio_processing.h"

static double energy(const std::vector<int16_t> &x, size_t from, size_t to) {
    double e = 0;
    for (size_t i = from; i < to; i++) e += double(x[i]) * x[i];
    return e / double(to - from);
}

int main() {
    const int rate = 48000, frame = rate / 100, seconds = 10, n = rate * seconds;
    const int delay = rate * 60 / 1000;
    std::mt19937 rng(7);
    std::normal_distribution<double> g(0, 1);

    // Speech-like far end: noise bursts, modulated at a syllable rate, low-passed.
    std::vector<int16_t> far(n), near(n), talker(n);
    double lp = 0, lp2 = 0;
    for (int i = 0; i < n; i++) {
        double env = 0.5 + 0.5 * std::sin(2 * M_PI * 4 * i / rate);
        lp += 0.2 * (g(rng) - lp);
        far[i] = int16_t(std::lround(std::clamp(6000 * env * lp, -32767.0, 32767.0)));
        // The near talker, from 5 s on: another voice-like signal.
        double env2 = 0.5 + 0.5 * std::sin(2 * M_PI * 3 * i / rate + 1);
        lp2 += 0.15 * (g(rng) - lp2);
        talker[i] = i >= 5 * rate ? int16_t(std::lround(4000 * env2 * lp2)) : 0;
    }
    for (int i = 0; i < n; i++) {
        double echo = 0;
        if (i >= delay) echo += 0.5 * far[i - delay];
        if (i >= delay + 480) echo += 0.2 * far[i - delay - 480];   // reverb
        if (i >= delay + 1440) echo += 0.1 * far[i - delay - 1440];
        near[i] = int16_t(std::lround(std::clamp(echo + talker[i], -32767.0, 32767.0)));
    }

    rtc::scoped_refptr<webrtc::AudioProcessing> apm = webrtc::AudioProcessingBuilder().Create();
    webrtc::AudioProcessing::Config c;
    c.echo_canceller.enabled = true;
    c.echo_canceller.mobile_mode = false;
    c.high_pass_filter.enabled = true;
    c.noise_suppression.enabled = getenv("APM_NS") != nullptr;
    c.gain_controller2.enabled = false;  // a gain stage would scale the result and blur the measure
    apm->ApplyConfig(c);
    apm->Initialize();
    webrtc::StreamConfig sc(rate, 1);

    std::vector<int16_t> out(near);
    for (int off = 0; off + frame <= n; off += frame) {
        std::vector<int16_t> r(far.begin() + off, far.begin() + off + frame);
        apm->ProcessReverseStream(r.data(), sc, sc, r.data());
        apm->set_stream_delay_ms(60);
        apm->ProcessStream(out.data() + off, sc, sc, out.data() + off);
    }

    // Echo alone: 3 s to 5 s, after convergence, before the near talker.
    double before = energy(near, 3 * rate, 5 * rate), after = energy(out, 3 * rate, 5 * rate);
    double erle = 10 * std::log10(before / std::max(after, 1e-9));
    // Double talk: 7 s to 10 s. What is left should be mostly the talker.
    double talkerE = energy(talker, 7 * rate, 10 * rate), outE = energy(out, 7 * rate, 10 * rate);
    double keep = 10 * std::log10(outE / std::max(talkerE, 1e-9));
    auto stats = apm->GetStatistics();
    std::printf("echo removed: %.1f dB (echo-only span)\n", erle);
    std::printf("near talker during double talk: %.1f dB relative to the clean talker\n", keep);
    std::printf("AEC3 delay estimate: %d ms\n", stats.delay_ms ? *stats.delay_ms : -1);
    bool ok = getenv("APM_NS") != nullptr || (erle >= 12 && keep > -6);
    std::printf("%s\n", ok ? "PASS" : "FAIL");
    return ok ? 0 : 1;
}
