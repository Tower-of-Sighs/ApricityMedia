package cc.sighs.apricitymedia;

import com.sighs.apricityui.registry.ApricityUIRegistry;
import net.minecraftforge.fml.common.Mod;

@Mod(ApricityMedia.MOD_ID)
public class ApricityMedia {
    public static final String MOD_ID = "apricitymedia";

    public ApricityMedia() {
        FFmpegRuntimeBootstrap.prewarmAsync();
        ApricityUIRegistry.scanPackage("cc.sighs.apricitymedia.element");
    }
}
