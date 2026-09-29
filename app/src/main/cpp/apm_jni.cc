// The app's own echo canceller (VOICE_SPEC §11.1, Phase 7): WebRTC's audio
// processing module with AEC3, noise suppression, adaptive digital gain and a
// high-pass filter, mono at the call's 48 kHz. What is played goes in as the
// render stream; what is captured comes out with the echo of it removed.
//
// APM takes 10 ms frames. Render and capture may run on different threads:
// APM locks each side itself.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <vector>

#include "api/audio/audio_processing.h"
#include "api/scoped_refptr.h"

namespace {

struct Apm {
    rtc::scoped_refptr<webrtc::AudioProcessing> apm;
    webrtc::StreamConfig stream;
    int frame;  // samples per 10 ms

    explicit Apm(int rate) : stream(rate, 1), frame(rate / 100) {}
};

Apm *of(jlong handle) {
    return reinterpret_cast<Apm *>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_fc_freer_call_engine_Apm_nativeCreate(JNIEnv *, jclass, jint sampleRate, jboolean noiseSuppression,
                                               jboolean gainControl) {
    auto *a = new Apm(sampleRate);
    a->apm = webrtc::AudioProcessingBuilder().Create();
    if (!a->apm) {
        delete a;
        return 0;
    }
    webrtc::AudioProcessing::Config c;
    c.echo_canceller.enabled = true;
    c.echo_canceller.mobile_mode = false;  // AEC3, not the old AECM
    c.high_pass_filter.enabled = true;
    c.noise_suppression.enabled = noiseSuppression;
    c.noise_suppression.level = webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;
    c.gain_controller2.enabled = gainControl;
    c.gain_controller2.adaptive_digital.enabled = gainControl;
    a->apm->ApplyConfig(c);
    a->apm->Initialize();
    __android_log_print(ANDROID_LOG_INFO, "FreerApm", "APM created at %d Hz: AEC3, NS %d, AGC2 %d", sampleRate,
                        noiseSuppression, gainControl);
    return reinterpret_cast<jlong>(a);
}

/** What is about to be played, in whole 10 ms frames. */
extern "C" JNIEXPORT void JNICALL
Java_com_fc_freer_call_engine_Apm_nativeRender(JNIEnv *env, jclass, jlong handle, jshortArray pcm, jint count) {
    Apm *a = of(handle);
    if (!a) return;
    std::vector<int16_t> buf(count);
    env->GetShortArrayRegion(pcm, 0, count, buf.data());
    for (int off = 0; off + a->frame <= count; off += a->frame) {
        a->apm->ProcessReverseStream(buf.data() + off, a->stream, a->stream, buf.data() + off);
    }
}

/**
 * What was captured, in whole 10 ms frames, processed in place. {@code delayMs}
 * is the estimate of how long before its capture the matching render went in;
 * AEC3 finds the true delay itself, so a rough one is enough.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_fc_freer_call_engine_Apm_nativeCapture(JNIEnv *env, jclass, jlong handle, jshortArray pcm, jint count,
                                                jint delayMs) {
    Apm *a = of(handle);
    if (!a) return;
    std::vector<int16_t> buf(count);
    env->GetShortArrayRegion(pcm, 0, count, buf.data());
    for (int off = 0; off + a->frame <= count; off += a->frame) {
        a->apm->set_stream_delay_ms(delayMs);
        a->apm->ProcessStream(buf.data() + off, a->stream, a->stream, buf.data() + off);
    }
    env->SetShortArrayRegion(pcm, 0, count, buf.data());
}

/** {echo return loss, its enhancement (both in dB ×100), delay found (ms)}; -1 where not known yet. */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_fc_freer_call_engine_Apm_nativeStats(JNIEnv *env, jclass, jlong handle) {
    jint out[3] = {-1, -1, -1};
    Apm *a = of(handle);
    if (a) {
        webrtc::AudioProcessingStats s = a->apm->GetStatistics();
        if (s.echo_return_loss) out[0] = static_cast<jint>(*s.echo_return_loss * 100);
        if (s.echo_return_loss_enhancement) out[1] = static_cast<jint>(*s.echo_return_loss_enhancement * 100);
        if (s.delay_ms) out[2] = *s.delay_ms;
    }
    jintArray arr = env->NewIntArray(3);
    env->SetIntArrayRegion(arr, 0, 3, out);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fc_freer_call_engine_Apm_nativeRelease(JNIEnv *, jclass, jlong handle) {
    delete of(handle);
}
