package dev.gdiniz.discorddownloaderbot.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

@Component
public class UrlPolicy {

    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final int MAX_URL_LENGTH = 2048;

    private final Resolver resolver;

    @Autowired
    public UrlPolicy() {
        this(InetAddress::getAllByName);
    }

    public UrlPolicy(Resolver resolver) {
        this.resolver = resolver;
    }

    public static String normalize(String raw) {
        if (raw == null) return null;
        String url = raw.strip();
        if (url.startsWith("<") && url.endsWith(">")) url = url.substring(1, url.length() - 1).strip();
        if (url.startsWith("||") && url.endsWith("||") && url.length() > 4) url = url.substring(2, url.length() - 2).strip();
        return url;
    }

    public Optional<String> rejectionReason(String url) {
        if (url == null || url.isBlank()) return Optional.of("empty url");
        if (url.length() > MAX_URL_LENGTH) return Optional.of("url too long");
        if (url.startsWith("-")) return Optional.of("url looks like a command-line option");
        if (url.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return Optional.of("url contains whitespace or control characters");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Optional.of("malformed url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return Optional.of("unsupported scheme: " + scheme);
        if (uri.getRawUserInfo() != null) return Optional.of("credentials in url");

        String host = uri.getHost();
        if (host == null || host.isBlank()) return Optional.of("missing host");
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")
                || lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")
                || lowerHost.endsWith(".cluster.local") || !lowerHost.contains(".") && !lowerHost.contains(":")) {
            return Optional.of("internal host: " + host);
        }

        try {
            for (InetAddress address : resolver.resolve(host)) {
                if (isInternal(address)) return Optional.of("host resolves to internal address: " + host);
            }
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0)
                    || (first == 198 && (second == 18 || second == 19))
                    || first >= 240;
        }
        if (b.length == 16) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return true;
            boolean mappedV4 = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mappedV4 = false;
            if (mappedV4 && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isInternal(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}
