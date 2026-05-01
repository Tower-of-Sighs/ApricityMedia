package cc.sighs.apricitymedia.client;

import cc.sighs.apricitymedia.ApricityMedia;
import cc.sighs.apricitymedia.FFmpegRuntimeBootstrap;
import com.mojang.blaze3d.platform.InputConstants;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Drawer;
import com.sighs.apricityui.instance.WorldWindow;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.phys.Vec3;
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
    private static boolean initFailTipShown = false;
    private static int titleScreenTicks = 0;

    public static final KeyMapping OPEN_DEMO = new KeyMapping(
            "key.apricityui.video.demo",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.apricityui"
    );
    private static final float WORLD_WINDOW_WIDTH = 382f;
    private static final float WORLD_WINDOW_HEIGHT = 400f;
    private static final int WORLD_WINDOW_MAX_DISTANCE = 12;
    // World window for in-world video demo
    private static WorldWindow worldWindow = null;

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

        Minecraft mc = Minecraft.getInstance();

        // Clean up world window if player left the world
        if (worldWindow != null && (mc.player == null || mc.level == null)) {
            WorldWindow.removeWindow(worldWindow);
            worldWindow = null;
        }

        while (OPEN_DEMO.consumeClick()) {
            toggleDemoDebounced();
        }

        tryShowInitFailTip(mc.screen);
    }

    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Post event) {
        if (!OPEN_DEMO.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        toggleDemoDebounced();
    }

    private static void toggleDemoDebounced() {
        long now = System.currentTimeMillis();
        if (now - lastToggleAtMs < 150L) {
            return;
        }
        lastToggleAtMs = now;
        toggleWorldDemo();
    }

    private static void toggleWorldDemo() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (worldWindow != null) {
            WorldWindow.removeWindow(worldWindow);
            worldWindow = null;
            LOGGER.debug("[ApricityMediaClient] World window closed");
            return;
        }

        Vec3 pos = mc.player.getEyePosition().add(mc.player.getViewVector(1.0F).scale(3.0));

        worldWindow = new WorldWindow(
                DEMO_PATH,
                pos,
                WORLD_WINDOW_WIDTH,
                WORLD_WINDOW_HEIGHT,
                WORLD_WINDOW_MAX_DISTANCE
        );
        // Face the player on placement, then stays static
        Vec3 toCamera = mc.player.getEyePosition().subtract(pos);
        double horiz = Math.sqrt(toCamera.x * toCamera.x + toCamera.z * toCamera.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(toCamera.z, toCamera.x)) - 90.0);
        float pitch = (float) (Math.toDegrees(Math.atan2(toCamera.y, horiz)));
        worldWindow.setRotation(yaw + 180.0f, -pitch);

        // Set explicit body dimensions so in-world layout is correct (no viewport in world mode)
        if (worldWindow.document != null && worldWindow.document.body != null) {
            worldWindow.document.body.setAttribute("style",
                    "width:" + WORLD_WINDOW_WIDTH + "px;height:" + WORLD_WINDOW_HEIGHT + "px;overflow:visible;margin:0;padding:0;");
            worldWindow.document.markDirty(worldWindow.document.body, Drawer.RELAYOUT | Drawer.REPAINT);
        }

        WorldWindow.addWindow(worldWindow);
        LOGGER.debug("[ApricityMediaClient] World window opened at {}", pos);
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
