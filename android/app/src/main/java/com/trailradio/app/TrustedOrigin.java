package com.trailradio.app;

import java.net.URI;

/** Match the app's secure origin by scheme, host, and port, not its serialized spelling. */
final class TrustedOrigin {
    static boolean matches(String origin) {
        if (origin == null) return false;
        try {
            URI uri = new URI(origin);
            String path = uri.getRawPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                && "trailradio.local".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getRawUserInfo() == null
                && (path == null || path.isEmpty() || "/".equals(path))
                && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (Exception error) { return false; }
    }
}
