package cc.sighs.apricitymedia.kjs;

import cc.sighs.apricitymedia.ApricityMedia;
import cc.sighs.apricitymedia.util.BilibiliLiveUtil;
import com.sighs.apricityui.registry.annotation.KJSBindings;

@KJSBindings(value = "ApricityMediaHls", modId = ApricityMedia.MOD_ID, isClient = true)
public final class ApricityMediaHls {
    private ApricityMediaHls() {
    }

    public static String getBilibiliPlayUrl(String roomId) {
        return BilibiliLiveUtil.getPlayUrl(roomId);
    }

    public static String getBilibiliPlayUrl(String roomId, int qn) {
        return BilibiliLiveUtil.getPlayUrl(roomId, qn);
    }
}
