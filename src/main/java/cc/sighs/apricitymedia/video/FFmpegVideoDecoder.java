package cc.sighs.apricitymedia.video;

import cc.sighs.apricitymedia.ApricityMedia;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVProgram;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FFmpegVideoDecoder implements IVideoDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(FFmpegVideoDecoder.class);
    private static final AtomicBoolean LOG_LEVEL_INITIALIZED = new AtomicBoolean(false);
    private static final long STATS_LOG_INTERVAL_MS = 3000L;
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
    private final boolean remoteInput;
    private final String source;

    private final AVPacket packet;
    private final AVFrame decodedFrame;
    private final SwsContext swsContext;
    private final int rgbaBufferSize;
    private final ArrayBlockingQueue<FrameSlot> freeSlots;
    private final List<FrameSlot> allSlots;
    private final ArrayDeque<VideoFrame> readyFrames = new ArrayDeque<>();

    private boolean eof = false;
    private boolean drainStarted = false;
    private int consecutiveReadErrors = 0;
    private long fallbackPtsMs = 0;
    private long lastEmittedPtsMs = Long.MIN_VALUE;

    private long packetsRead = 0;
    private long packetsVideo = 0;
    private long packetsKey = 0;
    private long sendEagain = 0;
    private long readEagain = 0;
    private long receiveErrors = 0;
    private long framesOutput = 0;
    private long framesDroppedFps = 0;
    private long framesDroppedNoSlot = 0;
    private long framesWithDecodeErrorFlags = 0;
    private long lastStatsLoggedAtMs = System.currentTimeMillis();
    private boolean loggedFirstFrames = false;

    public FFmpegVideoDecoder(String filePath, int targetWidth, int targetHeight, double maxFps, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect, Map<String, String> networkOptions) {
        ensureLogLevel();
        avformat.avformat_network_init();
        remoteInput = isRemotePath(filePath);
        source = filePath;

        formatContext = avformat.avformat_alloc_context();
        AVDictionary options = null;
        if (remoteInput) {
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
            LOGGER.error("FFmpeg video open input failed for {}", filePath);
            throw new IllegalStateException("avformat_open_input failed: " + filePath);
        }
        if (options != null) avutil.av_dict_free(options);
        if (avformat.avformat_find_stream_info(formatContext, (PointerPointer<?>) null) < 0) {
            LOGGER.error("FFmpeg video find stream info failed for {}", filePath);
            throw new IllegalStateException("avformat_find_stream_info failed: " + filePath);
        }

        int streamIndex = avformat.av_find_best_stream(formatContext, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, (PointerPointer<?>) null, 0);
        AVStream stream = streamIndex >= 0 ? formatContext.streams(streamIndex) : null;
        if (streamIndex < 0 || stream == null || stream.codecpar() == null) {
            // Fallback: pick the first video stream.
            streamIndex = -1;
            stream = null;
            for (int i = 0; i < formatContext.nb_streams(); i++) {
                AVStream candidate = formatContext.streams(i);
                if (candidate == null || candidate.codecpar() == null) continue;
                if (candidate.codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO) {
                    streamIndex = i;
                    stream = candidate;
                    break;
                }
            }
        }
        if (streamIndex < 0 || stream == null || stream.codecpar() == null) {
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
        // Be more tolerant for live streams that may start mid-GOP or contain occasional corruption.
        codecContext.flags2(codecContext.flags2() | avcodec.AV_CODEC_FLAG2_SHOW_ALL);
        codecContext.err_recognition(codecContext.err_recognition() | avcodec.AV_EF_IGNORE_ERR);
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
        int poolSize = Math.max(2, Math.min(8, (maxFps > 0.0001 ? 4 : 6)));
        freeSlots = new ArrayBlockingQueue<>(poolSize);
        allSlots = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            FrameSlot slot = FrameSlot.allocate(outWidth, outHeight, rgbaBufferSize);
            allSlots.add(slot);
            freeSlots.offer(slot);
        }

        logOpenInfo(stream, maxFps, targetWidth, targetHeight, networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions);
    }

    public FFmpegVideoDecoder(String filePath) {
        this(filePath, -1, -1, 0, 15000, 512, true, Map.of());
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static int safeNum(int v) {
        return v;
    }

    private static String mediaTypeName(int mediaType) {
        try {
            BytePointer ptr = avutil.av_get_media_type_string(mediaType);
            return ptr != null ? ptr.getString() : String.valueOf(mediaType);
        } catch (Exception ignored) {
            return String.valueOf(mediaType);
        }
    }

    private static String codecName(int codecId) {
        try {
            BytePointer ptr = avcodec.avcodec_get_name(codecId);
            return ptr != null ? ptr.getString() : String.valueOf(codecId);
        } catch (Exception ignored) {
            return String.valueOf(codecId);
        }
    }

    private static String pixFmtName(int pixFmt) {
        try {
            BytePointer ptr = avutil.av_get_pix_fmt_name(pixFmt);
            return ptr != null ? ptr.getString() : String.valueOf(pixFmt);
        } catch (Exception ignored) {
            return String.valueOf(pixFmt);
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

    public VideoFrame readNextFrame() {
        maybeLogStats(false);
        VideoFrame cached = readyFrames.pollFirst();
        if (cached != null) return cached;

        // Always drain already-decoded frames first (decoder may output multiple frames per packet).
        drainFramesToQueue(4, false);
        cached = readyFrames.pollFirst();
        if (cached != null) return cached;

        if (eof) {
            if (!drainStarted) {
                drainStarted = true;
                avcodec.avcodec_send_packet(codecContext, null);
            }
            drainFramesToQueue(8, false);
            return readyFrames.pollFirst();
        }

        // Try to read/decode a limited amount of work per call to avoid getting stuck in native calls for too long.
        for (int attempts = 0; attempts < 32 && !eof; attempts++) {
            int readRet = avformat.av_read_frame(formatContext, packet);
            if (readRet >= 0) {
                packetsRead++;
                consecutiveReadErrors = 0;
                try {
                    if (packet.stream_index() != videoStreamIndex) continue;
                    packetsVideo++;
                    if ((packet.flags() & avcodec.AV_PKT_FLAG_KEY) != 0) {
                        packetsKey++;
                    }

                    // Correct send/receive state machine: never drop packets on EAGAIN.
                    while (true) {
                        int sendRet = avcodec.avcodec_send_packet(codecContext, packet);
                        if (sendRet == 0) break;
                        if (sendRet == avutil.AVERROR_EAGAIN()) {
                            sendEagain++;
                            // Make room by draining at least one frame. If we're behind, it's better to drop than to deadlock.
                            drainFramesToQueue(2, true);
                            continue;
                        }
                        // Other errors: drop the packet and continue reading.
                        break;
                    }

                    // After sending, drain output frames.
                    drainFramesToQueue(8, false);
                    cached = readyFrames.pollFirst();
                    if (cached != null) return cached;
                } finally {
                    avcodec.av_packet_unref(packet);
                }
                continue;
            }

            if (readRet == avutil.AVERROR_EAGAIN()) {
                readEagain++;
                sleepQuiet(2);
                drainFramesToQueue(2, false);
                return readyFrames.pollFirst();
            }

            consecutiveReadErrors++;
            if (remoteInput && consecutiveReadErrors < 5) {
                sleepQuiet(10);
                drainFramesToQueue(2, false);
                return readyFrames.pollFirst();
            }

            eof = true;
            break;
        }

        return null;
    }

    @Override
    public long getDurationMs() {
        long dur = formatContext.duration();
        if (dur <= 0 || dur == avutil.AV_NOPTS_VALUE()) return -1;
        // AV_TIME_BASE is 1_000_000, convert to milliseconds
        return dur / 1000;
    }

    public void rewind() {
        eof = false;
        drainStarted = false;
        consecutiveReadErrors = 0;
        readyFrames.clear();
        fallbackPtsMs = 0;
        lastEmittedPtsMs = Long.MIN_VALUE;
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
            swscale.sws_freeContext(swsContext);
        } catch (Exception ignored) {
        }
        for (FrameSlot slot : allSlots) {
            try {
                slot.free();
            } catch (Exception ignored) {
            }
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

    private void drainFramesToQueue(int maxFrames, boolean dropIfNoSlot) {
        if (maxFrames <= 0) return;
        for (int i = 0; i < maxFrames; i++) {
            int ret = avcodec.avcodec_receive_frame(codecContext, decodedFrame);
            if (ret == avutil.AVERROR_EAGAIN() || ret == avutil.AVERROR_EOF()) {
                return;
            }
            if (ret < 0) {
                // Legitimate decode errors are non-fatal for our use-case; keep going.
                receiveErrors++;
                try {
                    avutil.av_frame_unref(decodedFrame);
                } catch (Exception ignored) {
                }
                continue;
            }

            VideoFrame out = null;
            try {
                out = convertDecodedFrame(dropIfNoSlot);
            } finally {
                try {
                    avutil.av_frame_unref(decodedFrame);
                } catch (Exception ignored) {
                }
            }
            if (out != null) {
                framesOutput++;
                readyFrames.offerLast(out);
            }
        }
    }

    private VideoFrame convertDecodedFrame(boolean dropIfNoSlot) {
        if (!loggedFirstFrames) {
            loggedFirstFrames = true;
            logFirstFrame(decodedFrame);
        }
        if (decodedFrame.decode_error_flags() != 0) {
            framesWithDecodeErrorFlags++;
            return null;
        }

        long ptsMs = resolvePtsMs(decodedFrame);
        int durationMs = resolveDurationMs(decodedFrame);
        if (minFrameIntervalMs > 0 && lastEmittedPtsMs != Long.MIN_VALUE && (ptsMs - lastEmittedPtsMs) < minFrameIntervalMs) {
            framesDroppedFps++;
            return null;
        }

        FrameSlot slot = dropIfNoSlot ? freeSlots.poll() : acquireSlotBlocking();
        if (slot == null) {
            framesDroppedNoSlot++;
            return null;
        }

        swscale.sws_scale(swsContext, decodedFrame.data(), decodedFrame.linesize(), 0, srcHeight, slot.frame.data(), slot.frame.linesize());
        lastEmittedPtsMs = ptsMs;
        slot.pixels.position(0);
        return new VideoFrame(slot.pixels, outWidth, outHeight, ptsMs, durationMs, () -> freeSlots.offer(slot));
    }

    private FrameSlot acquireSlotBlocking() {
        try {
            return freeSlots.take();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private void maybeLogStats(boolean force) {
        if (!ApricityMedia.isDev()) return;
        long now = System.currentTimeMillis();
        if (!force && (now - lastStatsLoggedAtMs) < STATS_LOG_INTERVAL_MS) return;
        lastStatsLoggedAtMs = now;
        LOGGER.info("FFmpeg video stats: pkts={}/video={} key={} send_eagain={} read_eagain={} recv_err={} frames_out={} drop_fps={} drop_noslot={} frame_decode_err_flags={} queued={} eof={} src={}",
                packetsRead,
                packetsVideo,
                packetsKey,
                sendEagain,
                readEagain,
                receiveErrors,
                framesOutput,
                framesDroppedFps,
                framesDroppedNoSlot,
                framesWithDecodeErrorFlags,
                readyFrames.size(),
                eof,
                source
        );
    }

    private void logFirstFrame(AVFrame frame) {
        if (!ApricityMedia.isDev()) return;
        try {
            byte pictType = avutil.av_get_picture_type_char(frame.pict_type());
            String pixFmt = pixFmtName(frame.format());
            LOGGER.info("FFmpeg video first decoded frame: pts={} dts={} best_effort_ts={} type={} size={}x{} fmt={} decode_error_flags=0x{} src={}",
                    frame.pts(),
                    frame.pkt_dts(),
                    frame.best_effort_timestamp(),
                    (char) pictType,
                    frame.width(),
                    frame.height(),
                    pixFmt,
                    Integer.toHexString(frame.decode_error_flags()),
                    source
            );
        } catch (Exception e) {
            LOGGER.info("FFmpeg video first decoded frame: <failed to dump frame meta> src={}", source, e);
        }
    }

    private void logOpenInfo(AVStream selectedStream, double maxFps, int targetWidth, int targetHeight, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect, Map<String, String> networkOptions) {
        if (!ApricityMedia.isDev()) return;
        try {
            LOGGER.info("FFmpeg video open: src={} remote={} streams={} programs={} selected_stream={} time_base={}/{} avg_fps={}/{} r_fps={}/{} target={}x{} out={}x{} max_fps={} timeout_ms={} buffer_kb={} reconnect={}",
                    source,
                    remoteInput,
                    formatContext.nb_streams(),
                    formatContext.nb_programs(),
                    videoStreamIndex,
                    safeNum(selectedStream.time_base() != null ? selectedStream.time_base().num() : 0),
                    safeNum(selectedStream.time_base() != null ? selectedStream.time_base().den() : 0),
                    safeNum(selectedStream.avg_frame_rate() != null ? selectedStream.avg_frame_rate().num() : 0),
                    safeNum(selectedStream.avg_frame_rate() != null ? selectedStream.avg_frame_rate().den() : 0),
                    safeNum(selectedStream.r_frame_rate() != null ? selectedStream.r_frame_rate().num() : 0),
                    safeNum(selectedStream.r_frame_rate() != null ? selectedStream.r_frame_rate().den() : 0),
                    targetWidth,
                    targetHeight,
                    outWidth,
                    outHeight,
                    maxFps,
                    networkTimeoutMs,
                    networkBufferKb,
                    networkReconnect
            );

            logPrograms();
            logStreams();

            String codecName = codecName(codecContext.codec_id());
            String pixFmt = pixFmtName(codecContext.pix_fmt());
            LOGGER.info("FFmpeg video codec: codec={} profile={} level={} b_frames={} pix_fmt={} src={}x{} out={}x{}",
                    codecName,
                    codecContext.profile(),
                    codecContext.level(),
                    codecContext.has_b_frames(),
                    pixFmt,
                    srcWidth,
                    srcHeight,
                    outWidth,
                    outHeight
            );

            if (networkOptions != null && !networkOptions.isEmpty()) {
                LOGGER.info("FFmpeg video network options: {}", networkOptions);
            }
        } catch (Exception e) {
            LOGGER.info("FFmpeg video open: <failed to dump input meta> src={}", source, e);
        }
    }

    private void logPrograms() {
        if (!ApricityMedia.isDev()) return;
        try {
            int nbPrograms = formatContext.nb_programs();
            for (int i = 0; i < nbPrograms; i++) {
                AVProgram program = formatContext.programs(i);
                if (program == null) continue;
                int n = program.nb_stream_indexes();
                StringBuilder streams = new StringBuilder();
                IntPointer idx = program.stream_index();
                for (int j = 0; j < n; j++) {
                    if (j != 0) streams.append(',');
                    streams.append(idx != null ? idx.get(j) : -1);
                }
                LOGGER.info("FFmpeg video program[{}]: id={} program_num={} pmt_pid={} pcr_pid={} streams=[{}] src={}",
                        i,
                        program.id(),
                        program.program_num(),
                        program.pmt_pid(),
                        program.pcr_pid(),
                        streams,
                        source
                );
            }
        } catch (Exception e) {
            LOGGER.debug("FFmpeg video programs dump failed src={}", source, e);
        }
    }

    private void logStreams() {
        if (!ApricityMedia.isDev()) return;
        int total = formatContext.nb_streams();
        for (int i = 0; i < total; i++) {
            try {
                AVStream st = formatContext.streams(i);
                if (st == null || st.codecpar() == null) continue;
                AVCodecParameters par = st.codecpar();
                String type = mediaTypeName(par.codec_type());
                String codec = codecName(par.codec_id());
                LOGGER.info("FFmpeg video stream[{}]: type={} codec={} id={} time_base={}/{} avg_fps={}/{} r_fps={}/{} size={}x{} fmt={} bit_rate={} extradata={} selected={} src={}",
                        i,
                        type,
                        codec,
                        st.id(),
                        safeNum(st.time_base() != null ? st.time_base().num() : 0),
                        safeNum(st.time_base() != null ? st.time_base().den() : 0),
                        safeNum(st.avg_frame_rate() != null ? st.avg_frame_rate().num() : 0),
                        safeNum(st.avg_frame_rate() != null ? st.avg_frame_rate().den() : 0),
                        safeNum(st.r_frame_rate() != null ? st.r_frame_rate().num() : 0),
                        safeNum(st.r_frame_rate() != null ? st.r_frame_rate().den() : 0),
                        par.width(),
                        par.height(),
                        par.format(),
                        par.bit_rate(),
                        par.extradata_size(),
                        (i == videoStreamIndex),
                        source
                );
            } catch (Exception e) {
                LOGGER.debug("FFmpeg video stream dump failed index={} src={}", i, source, e);
            }
        }
    }

    private long resolvePtsMs(AVFrame frame) {
        long pts = frame.best_effort_timestamp();
        if (pts < 0) {
            long next = fallbackPtsMs;
            fallbackPtsMs += Math.max(1L, resolveDurationMs(frame));
            return next;
        }
        double seconds = pts * rationalToDouble(timeBase);
        long ms = Math.max(0, Math.round(seconds * 1000.0));
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

    private record FrameSlot(AVFrame frame, BytePointer buffer, ByteBuffer pixels, int bufferSize) {

        static FrameSlot allocate(int width, int height, int bufferSize) {
            AVFrame frame = avutil.av_frame_alloc();
            BytePointer buffer = new BytePointer(avutil.av_malloc(bufferSize)).capacity(bufferSize);
            if (avutil.av_image_fill_arrays(frame.data(), frame.linesize(), buffer, avutil.AV_PIX_FMT_RGBA, width, height, 1) < 0) {
                throw new IllegalStateException("av_image_fill_arrays failed");
            }
            ByteBuffer pixels = buffer.position(0).capacity(bufferSize).asBuffer();
            return new FrameSlot(frame, buffer, pixels, bufferSize);
        }

        void free() {
            try {
                avutil.av_frame_free(frame);
            } catch (Exception ignored) {
            }
            try {
                avutil.av_free(buffer);
            } catch (Exception ignored) {
            }
        }
    }

    private static void ensureLogLevel() {
        if (LOG_LEVEL_INITIALIZED.compareAndSet(false, true)) {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        }
    }
}
