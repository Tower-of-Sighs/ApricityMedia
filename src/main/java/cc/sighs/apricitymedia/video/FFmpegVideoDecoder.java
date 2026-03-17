package cc.sighs.apricitymedia.video;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FFmpegVideoDecoder implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(FFmpegVideoDecoder.class);
    private static final AtomicBoolean LOG_LEVEL_INITIALIZED = new AtomicBoolean(false);
    private final AVFormatContext formatContext;
    private final AVCodecContext codecContext;
    private final int videoStreamIndex;
    private final int srcWidth;
    private final int srcHeight;
    private final int outWidth;
    private final int outHeight;
    private final int defaultDurationMs;
    private final AVRational timeBase;
    private final long minFrameIntervalMs;

    private final AVPacket packet;
    private final AVFrame decodedFrame;
    private final AVFrame rgbaFrame;
    private final SwsContext swsContext;
    private final BytePointer rgbaBuffer;
    private final int rgbaBufferSize;

    private boolean eof = false;
    private long fallbackPtsMs = 0;
    private long lastEmittedPtsMs = Long.MIN_VALUE;

    public FFmpegVideoDecoder(String filePath, int targetWidth, int targetHeight, double maxFps, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect) {
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
        }
        if (avformat.avformat_open_input(formatContext, filePath, null, options) < 0) {
            if (options != null) avutil.av_dict_free(options);
            LOGGER.error("FFmpeg video open input failed for {}", filePath);
            throw new IllegalStateException("avformat_open_input failed: " + filePath);
        }
        if (options != null) avutil.av_dict_free(options);
        if (avformat.avformat_find_stream_info(formatContext, (PointerPointer<?>) null) < 0) {
            LOGGER.error("FFmpeg video find stream info failed for {}", filePath);
            throw new IllegalStateException("avformat_find_stream_info failed: " + filePath);
        }

        int streamIndex = -1;
        AVStream stream = null;
        for (int i = 0; i < formatContext.nb_streams(); i++) {
            AVStream candidate = formatContext.streams(i);
            if (candidate == null || candidate.codecpar() == null) continue;
            if (candidate.codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO) {
                streamIndex = i;
                stream = candidate;
                break;
            }
        }
        if (streamIndex < 0 || stream == null) {
            LOGGER.error("FFmpeg video stream not found for {}", filePath);
            throw new IllegalStateException("No video stream found: " + filePath);
        }
        videoStreamIndex = streamIndex;
        timeBase = stream.time_base();

        AVCodec codec = avcodec.avcodec_find_decoder(stream.codecpar().codec_id());
        if (codec == null) {
            LOGGER.error("FFmpeg video codec unsupported for {}", filePath);
            throw new IllegalStateException("Unsupported codec: " + stream.codecpar().codec_id());
        }

        codecContext = avcodec.avcodec_alloc_context3(codec);
        if (avcodec.avcodec_parameters_to_context(codecContext, stream.codecpar()) < 0) {
            throw new IllegalStateException("avcodec_parameters_to_context failed");
        }
        if (avcodec.avcodec_open2(codecContext, codec, (AVDictionary) null) < 0) {
            throw new IllegalStateException("avcodec_open2 failed");
        }

        srcWidth = codecContext.width();
        srcHeight = codecContext.height();
        int[] resolved = resolveOutputSize(srcWidth, srcHeight, targetWidth, targetHeight);
        outWidth = resolved[0];
        outHeight = resolved[1];
        defaultDurationMs = resolveFrameDurationMs(stream);
        minFrameIntervalMs = maxFps > 0.0001 ? Math.max(1L, Math.round(1000.0 / maxFps)) : 0L;

        decodedFrame = avutil.av_frame_alloc();
        rgbaFrame = avutil.av_frame_alloc();
        packet = avcodec.av_packet_alloc();

        swsContext = swscale.sws_getContext(
                srcWidth, srcHeight, codecContext.pix_fmt(),
                outWidth, outHeight, avutil.AV_PIX_FMT_RGBA,
                swscale.SWS_BILINEAR, null, null, (DoublePointer) null
        );
        if (swsContext == null) {
            throw new IllegalStateException("sws_getContext failed");
        }

        rgbaBufferSize = avutil.av_image_get_buffer_size(avutil.AV_PIX_FMT_RGBA, outWidth, outHeight, 1);
        rgbaBuffer = new BytePointer(avutil.av_malloc(rgbaBufferSize)).capacity(rgbaBufferSize);

        if (avutil.av_image_fill_arrays(rgbaFrame.data(), rgbaFrame.linesize(), rgbaBuffer, avutil.AV_PIX_FMT_RGBA, outWidth, outHeight, 1) < 0) {
            throw new IllegalStateException("av_image_fill_arrays failed");
        }
    }

    public FFmpegVideoDecoder(String filePath) {
        this(filePath, -1, -1, 0, 15000, 512, true);
    }

    public VideoFrame readNextFrame() {
        if (eof) {
            return drainDecoder();
        }

        while (avformat.av_read_frame(formatContext, packet) >= 0) {
            try {
                if (packet.stream_index() != videoStreamIndex) continue;
                int sent = avcodec.avcodec_send_packet(codecContext, packet);
                if (sent < 0) continue;
                VideoFrame decoded = receiveFrame();
                if (decoded != null) return decoded;
            } finally {
                avcodec.av_packet_unref(packet);
            }
        }

        eof = true;
        avcodec.avcodec_send_packet(codecContext, null);
        return drainDecoder();
    }

    public void rewind() {
        eof = false;
        avformat.av_seek_frame(formatContext, videoStreamIndex, 0, avformat.AVSEEK_FLAG_BACKWARD);
        avcodec.avcodec_flush_buffers(codecContext);
    }

    @Override
    public void close() {
        try {
            avcodec.av_packet_free(packet);
        } catch (Exception ignored) {
        }
        try {
            avutil.av_frame_free(decodedFrame);
        } catch (Exception ignored) {
        }
        try {
            avutil.av_frame_free(rgbaFrame);
        } catch (Exception ignored) {
        }
        try {
            swscale.sws_freeContext(swsContext);
        } catch (Exception ignored) {
        }
        try {
            avutil.av_free(rgbaBuffer);
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

    private VideoFrame drainDecoder() {
        return receiveFrame();
    }

    private VideoFrame receiveFrame() {
        int ret = avcodec.avcodec_receive_frame(codecContext, decodedFrame);
        if (ret == avutil.AVERROR_EAGAIN() || ret == avutil.AVERROR_EOF()) return null;
        if (ret < 0) return null;

        long ptsMs = resolvePtsMs(decodedFrame);
        int durationMs = resolveDurationMs(decodedFrame);
        if (minFrameIntervalMs > 0 && lastEmittedPtsMs != Long.MIN_VALUE && (ptsMs - lastEmittedPtsMs) < minFrameIntervalMs) {
            return null;
        }

        swscale.sws_scale(swsContext, decodedFrame.data(), decodedFrame.linesize(), 0, srcHeight, rgbaFrame.data(), rgbaFrame.linesize());
        lastEmittedPtsMs = ptsMs;
        return new VideoFrame(toAbgrPixels(), outWidth, outHeight, ptsMs, durationMs);
    }

    private int[] toAbgrPixels() {
        BytePointer ptr = rgbaFrame.data(0);
        if (ptr == null) return new int[0];

        ByteBuffer buffer = ptr.position(0).capacity(rgbaBufferSize).asBuffer();
        int[] pixels = new int[outWidth * outHeight];
        int idx = 0;
        for (int i = 0; i < outWidth * outHeight; i++) {
            int r = buffer.get(idx++) & 0xFF;
            int g = buffer.get(idx++) & 0xFF;
            int b = buffer.get(idx++) & 0xFF;
            int a = buffer.get(idx++) & 0xFF;
            pixels[i] = (a << 24) | (b << 16) | (g << 8) | r;
        }
        return pixels;
    }

    private long resolvePtsMs(AVFrame frame) {
        long pts = frame.best_effort_timestamp();
        if (pts < 0) {
            long next = fallbackPtsMs;
            fallbackPtsMs += Math.max(1L, resolveDurationMs(frame));
            return next;
        }
        double seconds = pts * rationalToDouble(timeBase);
        long ms = (long) Math.max(0, Math.round(seconds * 1000.0));
        fallbackPtsMs = ms;
        return ms;
    }

    private int resolveDurationMs(AVFrame frame) {
        long duration = frame.duration();
        if (duration > 0) {
            double seconds = duration * rationalToDouble(timeBase);
            return (int) Math.max(1, Math.round(seconds * 1000.0));
        }
        return defaultDurationMs;
    }

    private static int resolveFrameDurationMs(AVStream stream) {
        AVRational fr = stream.avg_frame_rate();
        double fps = rationalToDouble(fr);
        if (fps <= 0.0001) {
            fps = rationalToDouble(stream.r_frame_rate());
        }
        if (fps <= 0.0001) {
            fps = 30.0;
        }
        return (int) Math.max(1, Math.round(1000.0 / fps));
    }

    private static double rationalToDouble(AVRational rational) {
        if (rational == null) return 0;
        int num = rational.num();
        int den = rational.den();
        if (num == 0 || den == 0) return 0;
        return 1.0 * num / den;
    }

    private static int[] resolveOutputSize(int srcW, int srcH, int targetW, int targetH) {
        if (srcW <= 0 || srcH <= 0) return new int[]{Math.max(1, targetW), Math.max(1, targetH)};
        if (targetW <= 0 && targetH <= 0) return new int[]{srcW, srcH};
        if (targetW > 0 && targetH > 0) return new int[]{targetW, targetH};

        if (targetW > 0) {
            int computedH = (int) Math.max(1, Math.round(1d * targetW * srcH / srcW));
            return new int[]{targetW, computedH};
        }
        int computedW = (int) Math.max(1, Math.round(1d * targetH * srcW / srcH));
        return new int[]{computedW, targetH};
    }

    private static boolean isRemotePath(String path) {
        if (path == null) return false;
        String v = path.trim().toLowerCase();
        return v.startsWith("http://")
                || v.startsWith("https://")
                || v.startsWith("rtsp://")
                || v.startsWith("rtmp://")
                || v.startsWith("mms://");
    }

    private static void ensureLogLevel() {
        if (LOG_LEVEL_INITIALIZED.compareAndSet(false, true)) {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        }
    }
}
