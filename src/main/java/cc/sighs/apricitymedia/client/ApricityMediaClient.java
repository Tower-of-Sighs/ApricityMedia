package cc.sighs.apricitymedia.client;

import cc.sighs.apricitymedia.ApricityMedia;
import cc.sighs.apricitymedia.FFmpegRuntimeBootstrap;
import com.mojang.blaze3d.platform.InputConstants;
import com.sighs.apricityui.init.Document;
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

@Mod.EventBusSubscriber(modid = ApricityMedia.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ApricityMediaClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApricityMediaClient.class);
    private static final String DEMO_PATH = "apricityui/video_demo.html";
    private static final String INIT_FAIL_TIP_PATH = "auivideo/ffmpeg_runtime_tip.html";
    private static final String INIT_FAIL_TIP_FALLBACK_PATH = "apricityui/auivideo/ffmpeg_runtime_tip.html";

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
        if (now - lastToggleAtMs < 150L) return;
        lastToggleAtMs = now;
        toggleDemo();
    }

    private static void toggleDemo() {
        if (Document.get(DEMO_PATH).isEmpty()) {
            Document.create(DEMO_PATH);
        } else {
            Document.remove(DEMO_PATH);
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
        if (!FFmpegRuntimeBootstrap.hasInitFailure()) return;
        if (Document.create(INIT_FAIL_TIP_PATH) != null || Document.create(INIT_FAIL_TIP_FALLBACK_PATH) != null) {
            initFailTipShown = true;
            LOGGER.warn("FFmpeg runtime init failed, showing tip: {}", FFmpegRuntimeBootstrap.getInitErrorMessage());
        }
    }
}