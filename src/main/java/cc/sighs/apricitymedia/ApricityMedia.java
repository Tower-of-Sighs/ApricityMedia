package cc.sighs.apricitymedia;

import com.sighs.apricityui.registry.ApricityUIRegistry;
import com.sighs.apricityui.script.KubeJS;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(ApricityMedia.MOD_ID)
public class ApricityMedia {
    public static final String MOD_ID = "apricitymedia";

    public static boolean isDev() {
        return !FMLEnvironment.production;
    }

    public ApricityMedia() {
        FFmpegRuntimeBootstrap.initializeOnStartup();
        ApricityUIRegistry.scanPackage("cc.sighs.apricitymedia.element");
        if (ModList.get().isLoaded("kubejs")) {
            KubeJS.scanPackage("cc.sighs.apricitymedia.kjs");
        }
    }
}
