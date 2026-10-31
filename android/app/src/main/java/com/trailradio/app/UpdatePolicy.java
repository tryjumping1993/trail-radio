package com.trailradio.app;

import java.net.URL;
import java.util.Locale;

/** Strict URL rules for the public release API and its APK redirects. */
final class UpdatePolicy {
    private static final String RELEASE_PATH = "/tryjumping1993/trail-radio/releases/download/";
    static boolean isInitialAsset(URL url) {
        return "https".equals(url.getProtocol()) && "github.com".equalsIgnoreCase(url.getHost())
            && url.getPort() == -1 && url.getUserInfo() == null
            && url.getPath().startsWith(RELEASE_PATH) && url.getPath().endsWith("/Trail-Radio.apk");
    }
    static boolean isAllowedHost(URL url, boolean asset) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(url.getProtocol()) || url.getPort() != -1 || url.getUserInfo() != null) return false;
        return asset ? (host.equals("github.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com")) : host.equals("api.github.com");
    }
}
