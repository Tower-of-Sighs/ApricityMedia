package cc.sighs.apricitymedia.hack;

import cpw.mods.cl.ModularURLHandler;
import cpw.mods.cl.ModuleClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FixedModularURLHandler implements URLStreamHandlerFactory {
    public static final FixedModularURLHandler INSTANCE = new FixedModularURLHandler();
    private Map<String, ModularURLHandler.IURLProvider> handlers;

    public static void init() {
        INSTANCE.handlers = ServiceLoader.load(ModuleClassLoader.class.getModule().getLayer(), ModularURLHandler.IURLProvider.class).stream()
                                         .map(ServiceLoader.Provider::get)
                                         .collect(Collectors.toMap(ModularURLHandler.IURLProvider::protocol, Function.identity()));
        INSTANCE.handlers.put("union",new FixedUnionURLStreamHandler());
    }

    @Override
    public URLStreamHandler createURLStreamHandler(final String protocol) {
        if (handlers == null) return null;
        if (handlers.containsKey(protocol)) {
            return new FunctionURLStreamHandler(handlers.get(protocol));
        }
        return null;
    }

    private static class FunctionURLStreamHandler extends URLStreamHandler {
        private final ModularURLHandler.IURLProvider iurlProvider;

        public FunctionURLStreamHandler(final ModularURLHandler.IURLProvider iurlProvider) {
            this.iurlProvider = iurlProvider;
        }

        @Override
        protected URLConnection openConnection(final URL u) throws IOException {
            return new FunctionURLConnection(u, this.iurlProvider);
        }
    }

    private static class FunctionURLConnection extends URLConnection {
        private final ModularURLHandler.IURLProvider provider;

        protected FunctionURLConnection(final URL url, final ModularURLHandler.IURLProvider provider) {
            super(url);
            this.provider = provider;
        }

        @Override
        public void connect() throws IOException {
        }

        @Override
        public InputStream getInputStream() throws IOException {
            try {
                return provider.inputStreamFunction().apply(url);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }

        @Override
        public int getContentLength() {
            var length = getContentLengthLong();
            if (length < 0 || length > Integer.MAX_VALUE) {
                return -1;
            }
            return (int) length;
        }

        @Override
        public long getContentLengthLong() {
            if (provider instanceof FixedURLProvider fixed) return fixed.getContentLength(url);
            else return 0;
        }

        @Override
        public long getLastModified() {
            if (provider instanceof FixedURLProvider fixed) return fixed.getLastModified(url);
            else return 0;
        }

    }

    public interface FixedURLProvider extends ModularURLHandler.IURLProvider {

        String protocol();

        Function<URL, InputStream> inputStreamFunction();

        default long getLastModified(URL url) {
            return 0;
        }

        default long getContentLength(URL url) {
            return -1;
        }
    }
}
