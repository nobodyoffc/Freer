package com.fc.freer.call.engine;

/**
 * One Opus frame and what the jitter buffer needs to know about it, between
 * the engine and whatever carries it: the spike's clear {@link SpikeFrame},
 * or a call's sealed MediaFrame (VOICE_SPEC §5).
 *
 * @param seq       counts every encoded frame, sent or not: a gap after a DTX
 *                  run is silence, which {@code afterDtx} says
 * @param timestamp 48 kHz media clock
 * @param level     -dBov, 0 loudest, 127 silence (RFC 6464)
 */
public record EncodedFrame(int ssrc, long seq, long timestamp, int level, boolean voiceActive, boolean afterDtx,
                           byte[] opus) {}
