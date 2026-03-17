package cc.sighs.apricitymedia;

import com.sighs.apricityui.registry.ApricityUIRegistry;
import net.fabricmc.api.ModInitializer;

public class ApricityMedia implements ModInitializer {

    @Override
    public void onInitialize() {
        FFmpegRuntimeBootstrap.prewarmAsync();
        ApricityUIRegistry.scanPackage("cc.sighs.auivideo.element");
    }
}
