// JNI bridge to AAudio for com.fc.freer.call.engine.AAudio: low-latency
// capture and playout for calls (VOICE_SPEC §9.2). Mono, 16-bit, blocking
// reads and writes from Java threads.
//
// Handles are pointers to a Stream, 0 on failure (not a sign test: arm64
// tags heap pointers in the top byte).

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>
#include <aaudio/AAudio.h>

typedef struct {
    AAudioStream *stream;
    int16_t *scratch;   // Java arrays are copied through this, never pinned while blocking
    int32_t scratchLen;
    int32_t rate;
    int input;
} Stream;

static int64_t nowNs(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t) t.tv_sec * 1000000000LL + t.tv_nsec;
}

static int16_t *scratchFor(Stream *s, int32_t n) {
    if (n > s->scratchLen) {
        int16_t *p = realloc(s->scratch, (size_t) n * sizeof(int16_t));
        if (p == NULL) return NULL;
        s->scratch = p;
        s->scratchLen = n;
    }
    return s->scratch;
}

// Voice-call settings either way: the input preset and output usage are what
// the platform keys its echo canceller and routing on.
JNIEXPORT jlong JNICALL
Java_com_fc_freer_call_engine_AAudio_open(JNIEnv *env, jclass cls, jboolean input, jint rate, jboolean exclusive) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return 0;
    AAudioStreamBuilder_setDirection(b, input ? AAUDIO_DIRECTION_INPUT : AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, exclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setChannelCount(b, 1);
    if (input) {
        AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
        AAudioStreamBuilder_setSessionId(b, AAUDIO_SESSION_ID_ALLOCATE);
    } else {
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_VOICE_COMMUNICATION);
        AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SPEECH);
    }
    AAudioStream *stream = NULL;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) return 0;
    if (AAudioStream_getSampleRate(stream) != rate || AAudioStream_getChannelCount(stream) != 1
            || AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16) {
        AAudioStream_close(stream);
        return 0;
    }
    if (!input) {
        // Two bursts: the least that plays without glitches on a good device.
        // Java grows it on underrun.
        AAudioStream_setBufferSizeInFrames(stream, 2 * AAudioStream_getFramesPerBurst(stream));
    }
    if (AAudioStream_requestStart(stream) != AAUDIO_OK) {
        AAudioStream_close(stream);
        return 0;
    }
    Stream *s = calloc(1, sizeof(Stream));
    if (s == NULL) {
        AAudioStream_close(stream);
        return 0;
    }
    s->stream = stream;
    s->rate = rate;
    s->input = input;
    return (jlong) (intptr_t) s;
}

/** @return frames read (0 on timeout), or a negative AAudio error */
JNIEXPORT jint JNICALL
Java_com_fc_freer_call_engine_AAudio_read(JNIEnv *env, jclass cls, jlong h, jshortArray buf, jint off, jint n,
                                          jlong timeoutNs) {
    Stream *s = (Stream *) (intptr_t) h;
    int16_t *p = scratchFor(s, n);
    if (p == NULL) return AAUDIO_ERROR_NO_MEMORY;
    aaudio_result_t r = AAudioStream_read(s->stream, p, n, timeoutNs);
    if (r > 0) (*env)->SetShortArrayRegion(env, buf, off, r, p);
    return r;
}

/** Blocks until all {@code n} frames are queued. @return frames written, or a negative AAudio error */
JNIEXPORT jint JNICALL
Java_com_fc_freer_call_engine_AAudio_write(JNIEnv *env, jclass cls, jlong h, jshortArray buf, jint off, jint n,
                                           jlong timeoutNs) {
    Stream *s = (Stream *) (intptr_t) h;
    int16_t *p = scratchFor(s, n);
    if (p == NULL) return AAUDIO_ERROR_NO_MEMORY;
    (*env)->GetShortArrayRegion(env, buf, off, n, p);
    return AAudioStream_write(s->stream, p, n, timeoutNs);
}

enum { Q_SHARING, Q_PERFORMANCE, Q_BURST, Q_BUFFER, Q_CAPACITY, Q_XRUNS, Q_SESSION, Q_LATENCY_MS };

JNIEXPORT jint JNICALL
Java_com_fc_freer_call_engine_AAudio_query(JNIEnv *env, jclass cls, jlong h, jint what) {
    Stream *s = (Stream *) (intptr_t) h;
    AAudioStream *st = s->stream;
    switch (what) {
        case Q_SHARING:     return AAudioStream_getSharingMode(st);
        case Q_PERFORMANCE: return AAudioStream_getPerformanceMode(st);
        case Q_BURST:       return AAudioStream_getFramesPerBurst(st);
        case Q_BUFFER:      return AAudioStream_getBufferSizeInFrames(st);
        case Q_CAPACITY:    return AAudioStream_getBufferCapacityInFrames(st);
        case Q_XRUNS:       return AAudioStream_getXRunCount(st);
        case Q_SESSION:     return AAudioStream_getSessionId(st);
        case Q_LATENCY_MS: {
            // The hardware presented/captured frame `pos` at `t`.
            int64_t pos = 0, t = 0;
            if (AAudioStream_getTimestamp(st, CLOCK_MONOTONIC, &pos, &t) != AAUDIO_OK) return -1;
            int64_t now = nowNs();
            if (s->input) {
                // Newest frame we have read was captured (read - pos) frames after t.
                int64_t read = AAudioStream_getFramesRead(st);
                int64_t captured = t + (read - pos) * 1000000000LL / s->rate;
                return (jint) ((now - captured) / 1000000);
            }
            // Newest frame written plays (written - pos) frames after t.
            int64_t written = AAudioStream_getFramesWritten(st);
            return (jint) ((written - pos) * 1000 / s->rate - (now - t) / 1000000);
        }
        default: return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_fc_freer_call_engine_AAudio_setBufferFrames(JNIEnv *env, jclass cls, jlong h, jint frames) {
    return AAudioStream_setBufferSizeInFrames(((Stream *) (intptr_t) h)->stream, frames);
}

JNIEXPORT void JNICALL
Java_com_fc_freer_call_engine_AAudio_close(JNIEnv *env, jclass cls, jlong h) {
    Stream *s = (Stream *) (intptr_t) h;
    if (s == NULL) return;
    AAudioStream_requestStop(s->stream);
    AAudioStream_close(s->stream);
    free(s->scratch);
    free(s);
}
