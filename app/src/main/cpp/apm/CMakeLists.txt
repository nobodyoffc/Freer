# WebRTC's audio processing module (AEC3 echo cancellation, noise suppression,
# gain control, high-pass filter), for the app's own echo canceller (VOICE_SPEC
# §11.1, Phase 7). Sources: freedesktop's webrtc-audio-processing 2.1
# (webrtc-audio-processing-2.1.tar.xz, sha256
# ae9302824b2038d394f10213cab05312c564a038434269f11dbf68f511f9f9fe), its
# webrtc/ tree unmodified; abseil-cpp 20240722.0 (sha256
# f50e5ac311a81382da7fa75b97310e4b9006474f9560ac46f54a9967f07d4ae3), the
# version it pins, without its tests. BSD and Apache 2.0 licensed.
#
# The source lists are the release's meson lists, for Android: generic C where
# it has ARM assembly, NEON where the ABI has it, SSE2 on x86_64.

set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
set(ABSL_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(abseil-cpp EXCLUDE_FROM_ALL)

set(W ${CMAKE_CURRENT_SOURCE_DIR}/webrtc)

set(APM_SOURCES
    ${W}/common_audio/audio_converter.cc
    ${W}/common_audio/audio_util.cc
    ${W}/common_audio/channel_buffer.cc
    ${W}/common_audio/fir_filter_c.cc
    ${W}/common_audio/fir_filter_factory.cc
    ${W}/common_audio/resampler/push_resampler.cc
    ${W}/common_audio/resampler/push_sinc_resampler.cc
    ${W}/common_audio/resampler/resampler.cc
    ${W}/common_audio/resampler/sinc_resampler.cc
    ${W}/common_audio/resampler/sinusoidal_linear_chirp_source.cc
    ${W}/common_audio/ring_buffer.c
    ${W}/common_audio/signal_processing/auto_correlation.c
    ${W}/common_audio/signal_processing/auto_corr_to_refl_coef.c
    ${W}/common_audio/signal_processing/complex_bit_reverse.c
    ${W}/common_audio/signal_processing/complex_fft.c
    ${W}/common_audio/signal_processing/copy_set_operations.c
    ${W}/common_audio/signal_processing/cross_correlation.c
    ${W}/common_audio/signal_processing/division_operations.c
    ${W}/common_audio/signal_processing/dot_product_with_scale.cc
    ${W}/common_audio/signal_processing/downsample_fast.c
    ${W}/common_audio/signal_processing/filter_ar.c
    ${W}/common_audio/signal_processing/filter_ar_fast_q12.c
    ${W}/common_audio/signal_processing/energy.c
    ${W}/common_audio/signal_processing/filter_ma_fast_q12.c
    ${W}/common_audio/signal_processing/get_hanning_window.c
    ${W}/common_audio/signal_processing/get_scaling_square.c
    ${W}/common_audio/signal_processing/ilbc_specific_functions.c
    ${W}/common_audio/signal_processing/levinson_durbin.c
    ${W}/common_audio/signal_processing/lpc_to_refl_coef.c
    ${W}/common_audio/signal_processing/min_max_operations.c
    ${W}/common_audio/signal_processing/randomization_functions.c
    ${W}/common_audio/signal_processing/real_fft.c
    ${W}/common_audio/signal_processing/refl_coef_to_lpc.c
    ${W}/common_audio/signal_processing/resample_48khz.c
    ${W}/common_audio/signal_processing/resample_by_2.c
    ${W}/common_audio/signal_processing/resample_by_2_internal.c
    ${W}/common_audio/signal_processing/resample.c
    ${W}/common_audio/signal_processing/resample_fractional.c
    ${W}/common_audio/signal_processing/spl_init.c
    ${W}/common_audio/signal_processing/spl_inl.c
    ${W}/common_audio/signal_processing/splitting_filter.c
    ${W}/common_audio/signal_processing/spl_sqrt.c
    ${W}/common_audio/signal_processing/sqrt_of_one_minus_x_squared.c
    ${W}/common_audio/signal_processing/vector_scaling_operations.c
    ${W}/common_audio/smoothing_filter.cc
    ${W}/common_audio/third_party/ooura/fft_size_128/ooura_fft.cc
    ${W}/common_audio/third_party/ooura/fft_size_256/fft4g.cc
    ${W}/common_audio/third_party/spl_sqrt_floor/spl_sqrt_floor.c
    ${W}/common_audio/vad/vad.cc
    ${W}/common_audio/vad/vad_core.c
    ${W}/common_audio/vad/vad_filterbank.c
    ${W}/common_audio/vad/vad_gmm.c
    ${W}/common_audio/vad/vad_sp.c
    ${W}/common_audio/vad/webrtc_vad.c
    ${W}/rtc_base/checks.cc
    ${W}/rtc_base/containers/flat_tree.cc
    ${W}/rtc_base/event.cc
    ${W}/rtc_base/event_tracer.cc
    ${W}/rtc_base/experiments/field_trial_parser.cc
    ${W}/rtc_base/logging.cc
    ${W}/rtc_base/memory/aligned_malloc.cc
    ${W}/rtc_base/platform_thread.cc
    ${W}/rtc_base/platform_thread_types.cc
    ${W}/rtc_base/race_checker.cc
    ${W}/rtc_base/random.cc
    ${W}/rtc_base/string_encode.cc
    ${W}/rtc_base/string_to_number.cc
    ${W}/rtc_base/string_utils.cc
    ${W}/rtc_base/strings/string_builder.cc
    ${W}/rtc_base/synchronization/sequence_checker_internal.cc
    ${W}/rtc_base/synchronization/yield_policy.cc
    ${W}/rtc_base/system/file_wrapper.cc
    ${W}/rtc_base/system_time.cc
    ${W}/rtc_base/time_utils.cc
    ${W}/rtc_base/zero_memory.cc
    ${W}/system_wrappers/source/cpu_features.cc
    ${W}/system_wrappers/source/denormal_disabler.cc
    ${W}/system_wrappers/source/field_trial.cc
    ${W}/system_wrappers/source/metrics.cc
    ${W}/system_wrappers/source/sleep.cc
    ${W}/api/audio/audio_frame.cc
    ${W}/api/audio/audio_processing.cc
    ${W}/api/audio/audio_processing_statistics.cc
    ${W}/api/audio/channel_layout.cc
    ${W}/api/audio/echo_canceller3_config.cc
    ${W}/api/rtp_headers.cc
    ${W}/api/rtp_packet_info.cc
    ${W}/api/task_queue/task_queue_base.cc
    ${W}/api/units/frequency.cc
    ${W}/api/units/time_delta.cc
    ${W}/api/units/timestamp.cc
    ${W}/api/video/color_space.cc
    ${W}/api/video/hdr_metadata.cc
    ${W}/api/video/video_content_type.cc
    ${W}/api/video/video_timing.cc
    ${W}/modules/audio_processing/aec_dump/null_aec_dump_factory.cc
    ${W}/modules/audio_processing/aec3/adaptive_fir_filter.cc
    ${W}/modules/audio_processing/aec3/adaptive_fir_filter_erl.cc
    ${W}/modules/audio_processing/aec3/aec3_common.cc
    ${W}/modules/audio_processing/aec3/aec3_fft.cc
    ${W}/modules/audio_processing/aec3/aec_state.cc
    ${W}/modules/audio_processing/aec3/alignment_mixer.cc
    ${W}/modules/audio_processing/aec3/api_call_jitter_metrics.cc
    ${W}/modules/audio_processing/aec3/block_buffer.cc
    ${W}/modules/audio_processing/aec3/block_delay_buffer.cc
    ${W}/modules/audio_processing/aec3/block_framer.cc
    ${W}/modules/audio_processing/aec3/block_processor.cc
    ${W}/modules/audio_processing/aec3/block_processor_metrics.cc
    ${W}/modules/audio_processing/aec3/clockdrift_detector.cc
    ${W}/modules/audio_processing/aec3/coarse_filter_update_gain.cc
    ${W}/modules/audio_processing/aec3/comfort_noise_generator.cc
    ${W}/modules/audio_processing/aec3/config_selector.cc
    ${W}/modules/audio_processing/aec3/decimator.cc
    ${W}/modules/audio_processing/aec3/dominant_nearend_detector.cc
    ${W}/modules/audio_processing/aec3/downsampled_render_buffer.cc
    ${W}/modules/audio_processing/aec3/echo_audibility.cc
    ${W}/modules/audio_processing/aec3/echo_canceller3.cc
    ${W}/modules/audio_processing/aec3/echo_path_delay_estimator.cc
    ${W}/modules/audio_processing/aec3/echo_path_variability.cc
    ${W}/modules/audio_processing/aec3/echo_remover.cc
    ${W}/modules/audio_processing/aec3/echo_remover_metrics.cc
    ${W}/modules/audio_processing/aec3/erle_estimator.cc
    ${W}/modules/audio_processing/aec3/erl_estimator.cc
    ${W}/modules/audio_processing/aec3/fft_buffer.cc
    ${W}/modules/audio_processing/aec3/filter_analyzer.cc
    ${W}/modules/audio_processing/aec3/frame_blocker.cc
    ${W}/modules/audio_processing/aec3/fullband_erle_estimator.cc
    ${W}/modules/audio_processing/aec3/matched_filter.cc
    ${W}/modules/audio_processing/aec3/matched_filter_lag_aggregator.cc
    ${W}/modules/audio_processing/aec3/moving_average.cc
    ${W}/modules/audio_processing/aec3/multi_channel_content_detector.cc
    ${W}/modules/audio_processing/aec3/refined_filter_update_gain.cc
    ${W}/modules/audio_processing/aec3/render_buffer.cc
    ${W}/modules/audio_processing/aec3/render_delay_buffer.cc
    ${W}/modules/audio_processing/aec3/render_delay_controller.cc
    ${W}/modules/audio_processing/aec3/render_delay_controller_metrics.cc
    ${W}/modules/audio_processing/aec3/render_signal_analyzer.cc
    ${W}/modules/audio_processing/aec3/residual_echo_estimator.cc
    ${W}/modules/audio_processing/aec3/reverb_decay_estimator.cc
    ${W}/modules/audio_processing/aec3/reverb_frequency_response.cc
    ${W}/modules/audio_processing/aec3/reverb_model.cc
    ${W}/modules/audio_processing/aec3/reverb_model_estimator.cc
    ${W}/modules/audio_processing/aec3/signal_dependent_erle_estimator.cc
    ${W}/modules/audio_processing/aec3/spectrum_buffer.cc
    ${W}/modules/audio_processing/aec3/stationarity_estimator.cc
    ${W}/modules/audio_processing/aec3/subband_erle_estimator.cc
    ${W}/modules/audio_processing/aec3/subband_nearend_detector.cc
    ${W}/modules/audio_processing/aec3/subtractor.cc
    ${W}/modules/audio_processing/aec3/subtractor_output_analyzer.cc
    ${W}/modules/audio_processing/aec3/subtractor_output.cc
    ${W}/modules/audio_processing/aec3/suppression_filter.cc
    ${W}/modules/audio_processing/aec3/suppression_gain.cc
    ${W}/modules/audio_processing/aec3/transparent_mode.cc
    ${W}/modules/audio_processing/aecm/aecm_core.cc
    ${W}/modules/audio_processing/aecm/echo_control_mobile.cc
    ${W}/modules/audio_processing/agc/agc.cc
    ${W}/modules/audio_processing/agc/agc_manager_direct.cc
    ${W}/modules/audio_processing/agc/legacy/analog_agc.cc
    ${W}/modules/audio_processing/agc/legacy/digital_agc.cc
    ${W}/modules/audio_processing/agc/loudness_histogram.cc
    ${W}/modules/audio_processing/agc/utility.cc
    ${W}/modules/audio_processing/agc2/adaptive_digital_gain_controller.cc
    ${W}/modules/audio_processing/agc2/agc2_testing_common.cc
    ${W}/modules/audio_processing/agc2/biquad_filter.cc
    ${W}/modules/audio_processing/agc2/clipping_predictor.cc
    ${W}/modules/audio_processing/agc2/clipping_predictor_level_buffer.cc
    ${W}/modules/audio_processing/agc2/compute_interpolated_gain_curve.cc
    ${W}/modules/audio_processing/agc2/cpu_features.cc
    ${W}/modules/audio_processing/agc2/fixed_digital_level_estimator.cc
    ${W}/modules/audio_processing/agc2/gain_applier.cc
    ${W}/modules/audio_processing/agc2/input_volume_controller.cc
    ${W}/modules/audio_processing/agc2/input_volume_stats_reporter.cc
    ${W}/modules/audio_processing/agc2/interpolated_gain_curve.cc
    ${W}/modules/audio_processing/agc2/limiter.cc
    ${W}/modules/audio_processing/agc2/limiter_db_gain_curve.cc
    ${W}/modules/audio_processing/agc2/noise_level_estimator.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/auto_correlation.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/features_extraction.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/lp_residual.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/pitch_search.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/pitch_search_internal.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/rnn.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/rnn_fc.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/rnn_gru.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/spectral_features.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/spectral_features_internal.cc
    ${W}/modules/audio_processing/agc2/saturation_protector.cc
    ${W}/modules/audio_processing/agc2/saturation_protector_buffer.cc
    ${W}/modules/audio_processing/agc2/speech_level_estimator.cc
    ${W}/modules/audio_processing/agc2/speech_probability_buffer.cc
    ${W}/modules/audio_processing/agc2/vad_wrapper.cc
    ${W}/modules/audio_processing/agc2/vector_float_frame.cc
    ${W}/modules/audio_processing/audio_buffer.cc
    ${W}/modules/audio_processing/audio_processing_builder_impl.cc
    ${W}/modules/audio_processing/audio_processing_impl.cc
    ${W}/modules/audio_processing/capture_levels_adjuster/audio_samples_scaler.cc
    ${W}/modules/audio_processing/capture_levels_adjuster/capture_levels_adjuster.cc
    ${W}/modules/audio_processing/echo_control_mobile_impl.cc
    ${W}/modules/audio_processing/echo_detector/circular_buffer.cc
    ${W}/modules/audio_processing/echo_detector/mean_variance_estimator.cc
    ${W}/modules/audio_processing/echo_detector/moving_max.cc
    ${W}/modules/audio_processing/echo_detector/normalized_covariance_estimator.cc
    ${W}/modules/audio_processing/gain_control_impl.cc
    ${W}/modules/audio_processing/gain_controller2.cc
    ${W}/modules/audio_processing/high_pass_filter.cc
    ${W}/modules/audio_processing/include/aec_dump.cc
    ${W}/modules/audio_processing/include/audio_frame_proxies.cc
    ${W}/modules/audio_processing/logging/apm_data_dumper.cc
    ${W}/modules/audio_processing/ns/fast_math.cc
    ${W}/modules/audio_processing/ns/histograms.cc
    ${W}/modules/audio_processing/ns/noise_estimator.cc
    ${W}/modules/audio_processing/ns/noise_suppressor.cc
    ${W}/modules/audio_processing/ns/ns_fft.cc
    ${W}/modules/audio_processing/ns/prior_signal_model.cc
    ${W}/modules/audio_processing/ns/prior_signal_model_estimator.cc
    ${W}/modules/audio_processing/ns/quantile_noise_estimator.cc
    ${W}/modules/audio_processing/ns/signal_model.cc
    ${W}/modules/audio_processing/ns/signal_model_estimator.cc
    ${W}/modules/audio_processing/ns/speech_probability_estimator.cc
    ${W}/modules/audio_processing/ns/suppression_params.cc
    ${W}/modules/audio_processing/ns/wiener_filter.cc
    ${W}/modules/audio_processing/residual_echo_detector.cc
    ${W}/modules/audio_processing/rms_level.cc
    ${W}/modules/audio_processing/splitting_filter.cc
    ${W}/modules/audio_processing/three_band_filter_bank.cc
    ${W}/modules/audio_processing/utility/cascaded_biquad_filter.cc
    ${W}/modules/audio_processing/utility/delay_estimator.cc
    ${W}/modules/audio_processing/utility/delay_estimator_wrapper.cc
    ${W}/modules/audio_processing/utility/pffft_wrapper.cc
    ${W}/modules/audio_processing/vad/gmm.cc
    ${W}/modules/audio_processing/vad/pitch_based_vad.cc
    ${W}/modules/audio_processing/vad/pitch_internal.cc
    ${W}/modules/audio_processing/vad/pole_zero_filter.cc
    ${W}/modules/audio_processing/vad/standalone_vad.cc
    ${W}/modules/audio_processing/vad/vad_audio_proc.cc
    ${W}/modules/audio_processing/vad/vad_circular_buffer.cc
    ${W}/modules/audio_processing/vad/voice_activity_detector.cc
    ${W}/modules/audio_processing/aecm/aecm_core_c.cc
    ${W}/modules/audio_coding/codecs/isac/main/source/filter_functions.c
    ${W}/modules/audio_coding/codecs/isac/main/source/isac_vad.c
    ${W}/modules/audio_coding/codecs/isac/main/source/pitch_estimator.c
    ${W}/modules/audio_coding/codecs/isac/main/source/pitch_filter.c
    ${W}/third_party/pffft/src/pffft.c
    ${W}/third_party/rnnoise/src/rnn_vad_weights.cc
    ${W}/modules/third_party/fft/fft.c
)

set(APM_NEON_SOURCES
    ${W}/common_audio/fir_filter_neon.cc
    ${W}/common_audio/resampler/sinc_resampler_neon.cc
    ${W}/common_audio/signal_processing/cross_correlation_neon.c
    ${W}/common_audio/signal_processing/downsample_fast_neon.c
    ${W}/common_audio/signal_processing/min_max_operations_neon.c
    ${W}/common_audio/third_party/ooura/fft_size_128/ooura_fft_neon.cc
    ${W}/modules/audio_processing/aecm/aecm_core_neon.cc
)

set(APM_SSE2_SOURCES
    ${W}/common_audio/fir_filter_sse.cc
    ${W}/common_audio/resampler/sinc_resampler_sse.cc
    ${W}/common_audio/third_party/ooura/fft_size_128/ooura_fft_sse2.cc
)

set(APM_AVX2_SOURCES
    ${W}/common_audio/fir_filter_avx2.cc
    ${W}/common_audio/resampler/sinc_resampler_avx2.cc
    ${W}/modules/audio_processing/aec3/adaptive_fir_filter_avx2.cc
    ${W}/modules/audio_processing/aec3/adaptive_fir_filter_erl_avx2.cc
    ${W}/modules/audio_processing/aec3/fft_data_avx2.cc
    ${W}/modules/audio_processing/aec3/matched_filter_avx2.cc
    ${W}/modules/audio_processing/aec3/vector_math_avx2.cc
    ${W}/modules/audio_processing/agc2/rnn_vad/vector_math_avx2.cc
)

set(APM_DEFINES WEBRTC_LIBRARY_IMPL WEBRTC_ENABLE_SYMBOL_EXPORT NDEBUG WEBRTC_APM_DEBUG_DUMP=0 WEBRTC_POSIX _GNU_SOURCE)
if(ANDROID)
    list(APPEND APM_SOURCES ${W}/rtc_base/system/warn_current_thread_is_deadlocked.cc)
    list(APPEND APM_DEFINES WEBRTC_LINUX WEBRTC_ANDROID)
    set(APM_ARCH ${ANDROID_ABI})
elseif(APPLE)
    # Only for the host test (apm_test): the app builds it for Android.
    list(APPEND APM_DEFINES WEBRTC_MAC)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "arm64|aarch64")
        set(APM_ARCH arm64-v8a)
    endif()
endif()

if(APM_ARCH STREQUAL "arm64-v8a")
    list(APPEND APM_SOURCES ${APM_NEON_SOURCES})
    list(APPEND APM_DEFINES WEBRTC_ARCH_ARM64 WEBRTC_HAS_NEON)
elseif(APM_ARCH STREQUAL "armeabi-v7a")
    list(APPEND APM_SOURCES ${APM_NEON_SOURCES})
    list(APPEND APM_DEFINES WEBRTC_HAS_NEON)
elseif(APM_ARCH STREQUAL "x86_64")
    # AVX2 routines are chosen at run time, where the CPU has them; SSE2 is every x86_64's.
    list(APPEND APM_SOURCES ${APM_SSE2_SOURCES} ${APM_AVX2_SOURCES})
    list(APPEND APM_DEFINES WAP_DISABLE_INLINE_SSE)
    set_source_files_properties(${APM_SSE2_SOURCES} PROPERTIES COMPILE_OPTIONS "-msse2")
    set_source_files_properties(${APM_AVX2_SOURCES} PROPERTIES COMPILE_OPTIONS "-mavx2;-mfma")
endif()

add_library(webrtc_apm STATIC ${APM_SOURCES})
target_include_directories(webrtc_apm PUBLIC ${W})
target_compile_definitions(webrtc_apm PUBLIC ${APM_DEFINES})
target_compile_features(webrtc_apm PUBLIC cxx_std_17)
# Real-time audio: optimise in debug builds too; the release's code is not ours to warn about.
target_compile_options(webrtc_apm PRIVATE -O2 -w)
target_link_libraries(webrtc_apm PUBLIC
    absl::base absl::strings absl::optional absl::algorithm_container absl::flat_hash_map
    absl::inlined_vector absl::memory absl::string_view absl::bits absl::nullability)
