package cc.sighs.apricitymedia.audio;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FFmpegAudioDecoder implements IAudioDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(FFmpegAudioDecoder.class);
    private static final AtomicBoolean LOG_LEVEL_INITIALIZED = new AtomicBoolean(false);
    private static final int OUT_SAMPLE_RATE = 48000;
    private static final int OUT_SAMPLE_FMT = avutil.AV_SAMPLE_FMT_S16;
    private static final int OUT_CHANNELS = 2;

    private final AVFormatContext formatContext;
    private final AVCodecContext codecContext;
    private final int audioStreamIndex;
    private final AVPacket packet;
    private final AVFrame frame;
    private final SwrContext swr;
    private final AVChannelLayout outLayout;
    private final AVChannelLayout inLayout;

    private boolean eof = false;
    private final PointerPointer<BytePointer> outPtrs = new PointerPointer<>(1);
    private boolean drainStarted = false;
    private BytePointer outBuffer;
    private int outBufferCapacity = 0;
    private int pendingBytes = 0;
    private int pendingPos = 0;

    public FFmpegAudioDecoder(String filePath, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect, Map<String, String> networkOptions) {
        ensureLogLevel();
        avformat.avformat_network_init();

        formatContext = avformat.avformat_alloc_context();
        AVDictionary options = null;
        if (isRemotePath(filePath)) {
            options = new AVDictionary();
            avutil.av_dict_set(options, "rw_timeout", String.valueOf(Math.max(1000L, networkTimeoutMs) * 1000L), 0);
            avutil.av_dict_set(options, "timeout", String.valueOf(Math.max(1000L, networkTimeoutMs) * 1000L), 0);
            avutil.av_dict_set(options, "buffer_size", String.valueOf(Math.max(64L, networkBufferKb) * 1024L), 0);
            avutil.av_dict_set(options, "reconnect", networkReconnect ? "1" : "0", 0);
            avutil.av_dict_set(options, "reconnect_streamed", networkReconnect ? "1" : "0", 0);
            avutil.av_dict_set(options, "reconnect_on_network_error", networkReconnect ? "1" : "0", 0);
            avutil.av_dict_set(options, "reconnect_on_http_error", networkReconnect ? "4xx,5xx" : "", 0);
            avutil.av_dict_set(options, "reconnect_delay_max", "2", 0);
            avutil.av_dict_set(options, "http_persistent", "1", 0);
            avutil.av_dict_set(options, "multiple_requests", "1", 0);
            avutil.av_dict_set(options, "analyzeduration", "5000000", 0);
            avutil.av_dict_set(options, "probesize", "1048576", 0);
            // Apply per-element custom options
            if (networkOptions != null) {
                for (Map.Entry<String, String> entry : networkOptions.entrySet()) {
                    avutil.av_dict_set(options, entry.getKey(), entry.getValue(), 0);
                }
            }
        }
        if (avformat.avformat_open_input(formatContext, filePath, null, options) < 0) {
            if (options != null) avutil.av_dict_free(options);
            LOGGER.error("FFmpeg audio open input failed for {}", filePath);
            throw new IllegalStateException("avformat_open_input failed: " + filePath);
        }
        if (options != null) avutil.av_dict_free(options);
        if (avformat.avformat_find_stream_info(formatContext, (PointerPointer<?>) null) < 0) {
            LOGGER.error("FFmpeg audio find stream info failed for {}", filePath);
            throw new IllegalStateException("avformat_find_stream_info failed: " + filePath);
        }

        int streamIndex = -1;
        AVStream stream = null;
        for (int i = 0; i < formatContext.nb_streams(); i++) {
            AVStream candidate = formatContext.streams(i);
            if (candidate == null || candidate.codecpar() == null) continue;
            if (candidate.codecpar().codec_type() == avutil.AVMEDIA_TYPE_AUDIO) {
                streamIndex = i;
                stream = candidate;
                break;
            }
        }
        if (streamIndex < 0) {
            LOGGER.error("FFmpeg audio stream not found for {}", filePath);
            throw new IllegalStateException("No audio stream found: " + filePath);
        }
        audioStreamIndex = streamIndex;

        AVCodec codec = avcodec.avcodec_find_decoder(stream.codecpar().codec_id());
        if (codec == null) {
            LOGGER.error("FFmpeg audio codec unsupported for {}", filePath);
            throw new IllegalStateException("Unsupported audio codec: " + stream.codecpar().codec_id());
        }

        codecContext = avcodec.avcodec_alloc_context3(codec);
        if (avcodec.avcodec_parameters_to_context(codecContext, stream.codecpar()) < 0) {
            throw new IllegalStateException("avcodec_parameters_to_context failed");
        }
        if (avcodec.avcodec_open2(codecContext, codec, (AVDictionary) null) < 0) {
            throw new IllegalStateException("avcodec_open2 failed");
        }

        packet = avcodec.av_packet_alloc();
        frame = avutil.av_frame_alloc();

        outLayout = new AVChannelLayout();
        avutil.av_channel_layout_default(outLayout, OUT_CHANNELS);

        inLayout = new AVChannelLayout();
        int copyResult = avutil.av_channel_layout_copy(inLayout, codecContext.ch_layout());
        if (copyResult < 0 || inLayout.nb_channels() <= 0) {
            avutil.av_channel_layout_default(inLayout, OUT_CHANNELS);
        }

        swr = swresample.swr_alloc();
        if (swr == null) {
            throw new IllegalStateException("swr_alloc failed");
        }
        int optsResult = swresample.swr_alloc_set_opts2(
                swr,
                outLayout,
                OUT_SAMPLE_FMT,
                OUT_SAMPLE_RATE,
                inLayout,
                codecContext.sample_fmt(),
                codecContext.sample_rate(),
                0,
                null
        );
        if (optsResult < 0) {
            throw new IllegalStateException("swr_alloc_set_opts2 failed");
        }
        if (swresample.swr_init(swr) < 0) {
            throw new IllegalStateException("swr_init failed");
        }
    }

    private static boolean isRemotePath(String path) {
        if (path == null) return false;
        String v = path.trim().toLowerCase();
        return v.contains("://")
                || v.contains("rtsp:")
                || v.contains("rtmp:")
                || v.contains("mms:");
    }

    @Override
    public int readNextPcmChunk(byte[] out, int offset, int length) {
        if (out == null || length <= 0) return 0;
        if (offset < 0 || offset >= out.length) return 0;
        length = Math.min(length, out.length - offset);

        // Serve pending bytes first.
        int copied = copyPending(out, offset, length);
        if (copied != 0) return copied;

        while (true) {
            if (eof) {
                if (!drainStarted) {
                    drainStarted = true;
                    avcodec.avcodec_send_packet(codecContext, null);
                }
                int produced = receivePcmToPending();
                if (produced <= 0) return -1;
                copied = copyPending(out, offset, length);
                if (copied != 0) return copied;
                continue;
            }

            // Read packets until we can produce some audio.
            while (avformat.av_read_frame(formatContext, packet) >= 0) {
                try {
                    if (packet.stream_index() != audioStreamIndex) continue;
                    int sent = avcodec.avcodec_send_packet(codecContext, packet);
                    if (sent < 0) continue;
                    int produced = receivePcmToPending();
                    if (produced > 0) {
                        copied = copyPending(out, offset, length);
                        if (copied != 0) return copied;
                    }
                } finally {
                    avcodec.av_packet_unref(packet);
                }
            }

            eof = true;
        }
    }

    @Override
    public long getDurationMs() {
        long dur = formatContext.duration();
        if (dur <= 0 || dur == avutil.AV_NOPTS_VALUE()) return -1;
        return dur / 1000;
    }

    public void rewind() {
        eof = false;
        drainStarted = false;
        pendingBytes = 0;
        pendingPos = 0;
        avformat.av_seek_frame(formatContext, audioStreamIndex, 0, avformat.AVSEEK_FLAG_BACKWARD);
        avcodec.avcodec_flush_buffers(codecContext);
        swresample.swr_close(swr);
        swresample.swr_init(swr);
    }

    public int outSampleRate() {
        return OUT_SAMPLE_RATE;
    }

    public int outChannels() {
        return OUT_CHANNELS;
    }

    @Override
    public void close() {
        try {
            if (outBuffer != null) {
                avutil.av_free(outBuffer);
            }
        } catch (Exception ignored) {
        }
        try {
            avcodec.av_packet_free(packet);
        } catch (Exception ignored) {
        }
        try {
            avutil.av_frame_free(frame);
        } catch (Exception ignored) {
        }
        try {
            swresample.swr_free(swr);
        } catch (Exception ignored) {
        }
        try {
            avutil.av_channel_layout_uninit(inLayout);
        } catch (Exception ignored) {
        }
        try {
            avutil.av_channel_layout_uninit(outLayout);
        } catch (Exception ignored) {
        }
        try {
            avcodec.avcodec_free_context(codecContext);
        } catch (Exception ignored) {
        }
        try {
            avformat.avformat_close_input(formatContext);
        } catch (Exception ignored) {
        }
    }

    private static void ensureLogLevel() {
        if (LOG_LEVEL_INITIALIZED.compareAndSet(false, true)) {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        }
    }

    private int copyPending(byte[] out, int offset, int length) {
        int available = pendingBytes - pendingPos;
        if (available <= 0) return 0;
        int toCopy = Math.min(length, available);
        BytePointer current = outBuffer;
        if (current == null) return 0;
        current.position(pendingPos);
        current.get(out, offset, toCopy);
        pendingPos += toCopy;
        if (pendingPos >= pendingBytes) {
            pendingPos = 0;
            pendingBytes = 0;
        }
        return toCopy;
    }

    private int receivePcmToPending() {
        int ret = avcodec.avcodec_receive_frame(codecContext, frame);
        if (ret == avutil.AVERROR_EAGAIN() || ret == avutil.AVERROR_EOF()) return 0;
        if (ret < 0) return 0;

        int inSamples = frame.nb_samples();
        if (inSamples <= 0) return 0;

        int outSamples = swresample.swr_get_out_samples(swr, inSamples);
        if (outSamples <= 0) return 0;

        int outLineSize = 0;
        int outBufSize = avutil.av_samples_get_buffer_size(new int[]{outLineSize}, OUT_CHANNELS, outSamples, OUT_SAMPLE_FMT, 1);
        if (outBufSize <= 0) return 0;

        ensureOutCapacity(outBufSize);
        outBuffer.position(0);
        outPtrs.put(outBuffer);

        int converted = swresample.swr_convert(swr, outPtrs, outSamples, frame.data(), inSamples);
        if (converted <= 0) {
            pendingBytes = 0;
            pendingPos = 0;
            return 0;
        }

        int bytes = avutil.av_samples_get_buffer_size(new int[]{0}, OUT_CHANNELS, converted, OUT_SAMPLE_FMT, 1);
        if (bytes <= 0) return 0;

        pendingPos = 0;
        pendingBytes = bytes;
        return bytes;
    }

    private void ensureOutCapacity(int requiredBytes) {
        if (outBuffer != null && outBufferCapacity >= requiredBytes) return;
        try {
            if (outBuffer != null) {
                avutil.av_free(outBuffer);
            }
        } catch (Exception ignored) {
        }
        outBuffer = new BytePointer(avutil.av_malloc(requiredBytes)).capacity(requiredBytes);
        outBufferCapacity = requiredBytes;
    }
}
