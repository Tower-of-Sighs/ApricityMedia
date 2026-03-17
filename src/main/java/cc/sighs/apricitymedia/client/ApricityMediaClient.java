package cc.sighs.apricitymedia.client;

import cc.sighs.apricitymedia.FFmpegRuntimeBootstrap;
import com.mojang.blaze3d.platform.InputConstants;
import com.sighs.apricityui.init.Document;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApricityMediaClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApricityMediaClient.class);
    private static final String DEMO_PATH = "apricityui/video_demo.html";
    private static final String INIT_FAIL_TIP_PATH = "auivideo/ffmpeg_runtime_tip.html";
    private static final String INIT_FAIL_TIP_FALLBACK_PATH = "apricityui/auivideo/ffmpeg_runtime_tip.html";
    private static long lastToggleAtMs = 0L;
    private static long lastScreenToggleAtMs = 0L;
    private static boolean initFailTipShown = false;
    private static int titleScreenTicks = 0;

    private static final KeyMapping OPEN_DEMO = new KeyMapping(
            "key.apricityui.video.demo",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.apricityui"
    );

    @Override
    public void onInitializeClient() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            KeyBindingHelper.registerKeyBinding(OPEN_DEMO);

            ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
                while (OPEN_DEMO.consumeClick()) {
                    long now = System.currentTimeMillis();
                    if (now - lastScreenToggleAtMs < 120L) {
                        continue;
                    }
                    toggleDemoDebounced();
                }
                tryShowInitFailTip(minecraft.screen);
            });

            ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
                ScreenKeyboardEvents.afterKeyPress(screen).register((Screen target, int keyCode, int scanCode, int modifiers) -> {
                    if (OPEN_DEMO.matches(keyCode, scanCode)) {
                        lastScreenToggleAtMs = System.currentTimeMillis();
                        toggleDemoDebounced();
                    }
                });
            });
        }
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
