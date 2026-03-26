package cc.sighs.apricitymedia.client;

import cc.sighs.apricitymedia.ApricityMedia;
import cc.sighs.apricitymedia.FFmpegRuntimeBootstrap;
import com.mojang.blaze3d.platform.InputConstants;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.WeakHashMap;

@Mod.EventBusSubscriber(modid = ApricityMedia.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ApricityMediaClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApricityMediaClient.class);
    private static final String DEMO_PATH = "apricityui/video_demo.html";
    private static final String INIT_FAIL_TIP_PATH = "auivideo/ffmpeg_runtime_tip.html";
    private static final String INIT_FAIL_TIP_FALLBACK_PATH = "apricityui/auivideo/ffmpeg_runtime_tip.html";
    private static final String MODE_LOCAL_VIDEO = "localVideo";
    private static final String MODE_LOCAL_AUDIO = "localAudio";
    private static final String MODE_NET_VIDEO = "netVideo";
    private static final String MODE_NET_AUDIO = "netAudio";
    private static final String NET_VIDEO_SRC = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
    private static final String NET_AUDIO_SRC = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
    private static final Map<Document, DemoState> DEMO_STATES = new WeakHashMap<>();

    private static long lastToggleAtMs = 0L;
    private static long lastScreenToggleAtMs = 0L;
    private static boolean initFailTipShown = false;
    private static int titleScreenTicks = 0;

    public static final KeyMapping OPEN_DEMO = new KeyMapping(
            "key.apricityui.video.demo",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.apricityui"
    );

    private ApricityMediaClient() {}

    @Mod.EventBusSubscriber(modid = ApricityMedia.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_DEMO);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (OPEN_DEMO.consumeClick()) {
            long now = System.currentTimeMillis();
            if (now - lastScreenToggleAtMs < 120L) {
                continue;
            }
            toggleDemoDebounced();
        }
        bindDemoEvents();
        Screen currentScreen = Minecraft.getInstance().screen;
        tryShowInitFailTip(currentScreen);
    }

    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Post event) {
        if (!OPEN_DEMO.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        lastScreenToggleAtMs = System.currentTimeMillis();
        toggleDemoDebounced();
    }

    private static void toggleDemoDebounced() {
        long now = System.currentTimeMillis();
        long delta = now - lastToggleAtMs;
        if (delta < 150L) {
            return;
        }
        lastToggleAtMs = now;
        toggleDemo();
    }

    private static void toggleDemo() {
        boolean exists = !Document.get(DEMO_PATH).isEmpty();
        if (!exists) {
            Document.create(DEMO_PATH);
        } else {
            Document.remove(DEMO_PATH);
        }
    }

    private static void bindDemoEvents() {
        for (Document document : Document.get(DEMO_PATH)) {
            DemoState state = DEMO_STATES.computeIfAbsent(document, key -> new DemoState());
            if (state.bound) continue;
            state.video = document.querySelector("#demoVideo");
            state.localAudio = document.querySelector("#demoAudio");
            state.netAudio = document.querySelector("#demoNetAudio");
            state.btnVideo = document.querySelector("#btnVideo");
            state.btnAudio = document.querySelector("#btnAudio");
            state.btnNetVideo = document.querySelector("#btnNetVideo");
            state.btnNetAudio = document.querySelector("#btnNetAudio");
            state.status = document.querySelector("#status");
            if (!state.isReady()) continue;
            state.btnVideo.addEventListener("mousedown", event -> toggleMode(state, MODE_LOCAL_VIDEO));
            state.btnAudio.addEventListener("mousedown", event -> toggleMode(state, MODE_LOCAL_AUDIO));
            state.btnNetVideo.addEventListener("mousedown", event -> toggleMode(state, MODE_NET_VIDEO));
            state.btnNetAudio.addEventListener("mousedown", event -> toggleMode(state, MODE_NET_AUDIO));
            stopAll(state);
            updateStatus(state, "就绪", "");
            state.bound = true;
        }
    }

    private static void toggleMode(DemoState state, String mode) {
        if (mode.equals(state.activeMode)) {
            state.activePaused = !state.activePaused;
            pauseMode(state, mode, state.activePaused);
            updateStatus(state, modeText(mode, state.activePaused), mode);
            return;
        }
        startMode(state, mode);
    }

    private static void startMode(DemoState state, String mode) {
        stopAll(state);
        setBoolAttr(state.video, "autoplay", true);
        setBoolAttr(state.localAudio, "autoplay", true);
        setBoolAttr(state.netAudio, "autoplay", true);
        if (MODE_LOCAL_VIDEO.equals(mode)) {
            state.video.setAttribute("src", "demo.mp4");
            setBoolAttr(state.video, "muted", false);
            setBoolAttr(state.video, "paused", false);
        } else if (MODE_LOCAL_AUDIO.equals(mode)) {
            state.localAudio.setAttribute("src", "demo.mp3");
            setBoolAttr(state.localAudio, "paused", false);
        } else if (MODE_NET_VIDEO.equals(mode)) {
            state.video.setAttribute("src", NET_VIDEO_SRC);
            applyNetworkAttrs(state.video);
            setBoolAttr(state.video, "muted", false);
            setBoolAttr(state.video, "paused", false);
        } else if (MODE_NET_AUDIO.equals(mode)) {
            state.netAudio.setAttribute("src", NET_AUDIO_SRC);
            applyNetworkAttrs(state.netAudio);
            setBoolAttr(state.netAudio, "paused", false);
        }
        state.activeMode = mode;
        state.activePaused = false;
        updateStatus(state, modeText(mode, false), mode);
    }

    private static void pauseMode(DemoState state, String mode, boolean paused) {
        if (MODE_LOCAL_VIDEO.equals(mode) || MODE_NET_VIDEO.equals(mode)) {
            setBoolAttr(state.video, "paused", paused);
            return;
        }
        if (MODE_LOCAL_AUDIO.equals(mode)) {
            setBoolAttr(state.localAudio, "paused", paused);
            return;
        }
        if (MODE_NET_AUDIO.equals(mode)) {
            setBoolAttr(state.netAudio, "paused", paused);
        }
    }

    private static void stopAll(DemoState state) {
        setBoolAttr(state.video, "paused", true);
        setBoolAttr(state.localAudio, "paused", true);
        setBoolAttr(state.netAudio, "paused", true);
    }

    private static void applyNetworkAttrs(Element element) {
        if (element == null) return;
        element.setAttribute("network-timeout-ms", "8000");
        element.setAttribute("network-buffer-kb", "768");
        element.setAttribute("network-reconnect", "true");
        element.setAttribute("preload", "metadata");
        element.setAttribute("max-fps", "30");
        element.setAttribute("decode-width", "960");
        element.setAttribute("drop-frames", "true");
    }

    private static void updateStatus(DemoState state, String text, String active) {
        if (state.status != null) {
            state.status.setAttribute("innerText", text);
        }
        state.btnVideo.setAttribute("class", MODE_LOCAL_VIDEO.equals(active) ? "btn primary" : "btn");
        state.btnAudio.setAttribute("class", MODE_LOCAL_AUDIO.equals(active) ? "btn primary" : "btn");
        state.btnNetVideo.setAttribute("class", MODE_NET_VIDEO.equals(active) ? "btn primary subtle" : "btn subtle");
        state.btnNetAudio.setAttribute("class", MODE_NET_AUDIO.equals(active) ? "btn primary subtle" : "btn subtle");
    }

    private static void setBoolAttr(Element element, String name, boolean value) {
        if (element == null) return;
        element.setAttribute(name, value ? "true" : "false");
    }

    private static String modeText(String mode, boolean paused) {
        if (MODE_LOCAL_VIDEO.equals(mode)) return paused ? "本地视频已暂停" : "本地视频播放中";
        if (MODE_LOCAL_AUDIO.equals(mode)) return paused ? "本地音频已暂停" : "本地音频播放中";
        if (MODE_NET_VIDEO.equals(mode)) return paused ? "网络视频已暂停" : "网络视频播放中";
        if (MODE_NET_AUDIO.equals(mode)) return paused ? "网络音频已暂停" : "网络音频播放中";
        return "就绪";
    }

    private static final class DemoState {
        private Element video;
        private Element localAudio;
        private Element netAudio;
        private Element btnVideo;
        private Element btnAudio;
        private Element btnNetVideo;
        private Element btnNetAudio;
        private Element status;
        private String activeMode = "";
        private boolean activePaused = true;
        private boolean bound = false;

        private boolean isReady() {
            return video != null && localAudio != null && netAudio != null
                    && btnVideo != null && btnAudio != null && btnNetVideo != null && btnNetAudio != null;
        }
    }

    private static void tryShowInitFailTip(Screen screen) {
        if (initFailTipShown) return;
        if (screen instanceof TitleScreen) {
            titleScreenTicks++;
        } else {
            titleScreenTicks = 0;
            return;
        }
        if (titleScreenTicks < 20) return;
        boolean hasFailure = FFmpegRuntimeBootstrap.hasInitFailure();
        if (!hasFailure) return;
        LOGGER.warn("ApricityMediaClient detected FFmpeg init failure on title screen, error={}",
                FFmpegRuntimeBootstrap.getInitErrorMessage());
        boolean createdPrimary = Document.create(INIT_FAIL_TIP_PATH) != null;
        boolean createdFallback = false;
        if (!createdPrimary) {
            createdFallback = Document.create(INIT_FAIL_TIP_FALLBACK_PATH) != null;
        }
        if (createdPrimary || createdFallback) {
            initFailTipShown = true;
            LOGGER.warn("FFmpeg runtime init failed, showing tip, primaryCreated={}, fallbackCreated={}",
                    createdPrimary, createdFallback);
        } else {
            LOGGER.warn("FFmpeg runtime init failed, but tip document creation failed for both paths");
        }
    }
}
