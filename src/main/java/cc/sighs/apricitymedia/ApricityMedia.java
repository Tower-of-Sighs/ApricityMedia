package cc.sighs.apricitymedia;

import cc.sighs.apricitymedia.client.ApricityMediaClient;
import com.sighs.apricityui.registry.ApricityUIRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(ApricityMedia.MOD_ID)
public class ApricityMedia {
    public static final String MOD_ID = "apricitymedia";

    public ApricityMedia() {
        FFmpegRuntimeBootstrap.prewarmAsync();
        ApricityUIRegistry.scanPackage("cc.sighs.apricitymedia.element");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ApricityMediaClient.register(FMLJavaModLoadingContext.get().getModEventBus());
        }
    }
}
