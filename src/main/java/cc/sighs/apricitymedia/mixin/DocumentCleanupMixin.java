package cc.sighs.apricitymedia.mixin;

import cc.sighs.apricitymedia.element.Audio;
import cc.sighs.apricitymedia.element.Video;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.UUID;

@Mixin(value = Document.class, remap = false)
public class DocumentCleanupMixin {
    @Inject(method = "remove(Ljava/lang/String;)V", at = @At("HEAD"), remap = false)
    private static void auiVideo$beforeRemoveByPath(String path, CallbackInfo ci) {
        ArrayList<Document> targets = Document.get(path);
        for (Document document : targets) {
            cleanupDocument(document);
        }
    }

    @Inject(method = "remove(Ljava/util/UUID;)V", at = @At("HEAD"), remap = false)
    private static void auiVideo$beforeRemoveByUuid(UUID uuid, CallbackInfo ci) {
        for (Document document : Document.getAll()) {
            if (document.is(uuid)) {
                cleanupDocument(document);
            }
        }
    }

    private static void cleanupDocument(Document document) {
        if (document == null) return;
        for (Element element : document.getElements()) {
            if (element instanceof Video video) {
                video.closeMedia();
            } else if (element instanceof Audio audio) {
                audio.closeMedia();
            }
        }
    }
}

