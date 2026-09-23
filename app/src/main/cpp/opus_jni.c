// JNI bridge to libopus for com.fc.freer.call.engine.Opus (VOICE_SPEC §9.1).
// Mono, 48 kHz. Handles are the native encoder/decoder pointers as jlong, 0 on
// failure. Not a sign test: arm64 Android tags heap pointers in the top byte,
// so a valid handle can be negative.

#include <jni.h>
#include <stdint.h>
#include "opus.h"

JNIEXPORT jlong JNICALL
Java_com_fc_freer_call_engine_Opus_encoderCreate(JNIEnv *env, jclass cls, jint sampleRate) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sampleRate, 1, OPUS_APPLICATION_VOIP, &err);
    return err == OPUS_OK ? (jlong) (intptr_t) enc : 0;
}

JNIEXPORT jint JNICALL
Java_com_fc_freer_call_engine_Opus_encoderCtl(JNIEnv *env, jclass cls, jlong handle, jint request, jint value) {
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) handle;
    switch (request) {
        case OPUS_SET_BITRATE_REQUEST:          return opus_encoder_ctl(enc, OPUS_SET_BITRATE(value));
        case OPUS_SET_VBR_REQUEST:              return opus_encoder_ctl(enc, OPUS_SET_VBR(value));
        case OPUS_SET_COMPLEXITY_REQUEST:       return opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(value));
        case OPUS_SET_INBAND_FEC_REQUEST:       return opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(value));
        case OPUS_SET_PACKET_LOSS_PERC_REQUEST: return opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(value));
        case OPUS_SET_DTX_REQUEST:              return opus_encoder_ctl(enc, OPUS_SET_DTX(value));
        case OPUS_SET_SIGNAL_REQUEST:           return opus_encoder_ctl(enc, OPUS_SET_SIGNAL(value));
        default:                                return OPUS_UNIMPLEMENTED;
    }
}

JNIEXPORT jint JNICALL
Java_com_fc_freer_call_engine_Opus_encode(JNIEnv *env, jclass cls, jlong handle,
                                          jshortArray pcm, jint frameSize, jbyteArray out) {
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) handle;
    jshort *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    jbyte *dst = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    jint n = opus_encode(enc, in, frameSize, (unsigned char *) dst, (*env)->GetArrayLength(env, out));
    (*env)->ReleasePrimitiveArrayCritical(env, out, dst, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
Java_com_fc_freer_call_engine_Opus_encoderDestroy(JNIEnv *env, jclass cls, jlong handle) {
    opus_encoder_destroy((OpusEncoder *) (intptr_t) handle);
}

JNIEXPORT jlong JNICALL
Java_com_fc_freer_call_engine_Opus_decoderCreate(JNIEnv *env, jclass cls, jint sampleRate) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sampleRate, 1, &err);
    return err == OPUS_OK ? (jlong) (intptr_t) dec : 0;
}

// data == NULL: packet-loss concealment. fec: decode the FEC copy of the
// PREVIOUS frame carried in `data` (the frame after a lost one).
JNIEXPORT jint JNICALL
Java_com_fc_freer_call_engine_Opus_decode(JNIEnv *env, jclass cls, jlong handle, jbyteArray data,
                                          jint length, jshortArray pcm, jint frameSize, jboolean fec) {
    OpusDecoder *dec = (OpusDecoder *) (intptr_t) handle;
    jbyte *src = data == NULL ? NULL : (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    jshort *out = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    jint n = opus_decode(dec, (const unsigned char *) src, src == NULL ? 0 : length, out, frameSize, fec ? 1 : 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, out, 0);
    if (src != NULL) (*env)->ReleasePrimitiveArrayCritical(env, data, src, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
Java_com_fc_freer_call_engine_Opus_decoderDestroy(JNIEnv *env, jclass cls, jlong handle) {
    opus_decoder_destroy((OpusDecoder *) (intptr_t) handle);
}
