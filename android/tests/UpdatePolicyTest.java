package com.trailradio.app;

import java.net.URL;

public final class UpdatePolicyTest {
    private static void expect(boolean actual, boolean expected, String description) {
        if (actual != expected) throw new AssertionError(description);
    }
    public static void main(String[] args) throws Exception {
        expect(UpdatePolicy.isInitialAsset(new URL("https://github.com/tryjumping1993/trail-radio/releases/download/v1.0.2/Trail-Radio.apk")), true, "official release");
        expect(UpdatePolicy.isInitialAsset(new URL("https://github.com/attacker/trail-radio/releases/download/v1/Trail-Radio.apk")), false, "other owner");
        expect(UpdatePolicy.isInitialAsset(new URL("https://github.com/tryjumping1993/trail-radio/releases/download/v1/other.apk")), false, "other asset");
        expect(UpdatePolicy.isInitialAsset(new URL("http://github.com/tryjumping1993/trail-radio/releases/download/v1/Trail-Radio.apk")), false, "insecure scheme");
        expect(UpdatePolicy.isAllowedHost(new URL("https://release-assets.githubusercontent.com/file"), true), true, "GitHub asset redirect");
        expect(UpdatePolicy.isAllowedHost(new URL("https://github.com.attacker.test/file"), true), false, "deceptive host");
        expect(UpdatePolicy.isAllowedHost(new URL("https://github.com:444/file"), true), false, "unexpected port");
        expect(UpdatePolicy.isAllowedHost(new URL("https://attacker@github.com/file"), true), false, "userinfo");
        expect(UpdatePolicy.isAllowedHost(new URL("https://api.github.com/repos"), false), true, "GitHub API");
        expect(UpdatePolicy.isAllowedHost(new URL("https://github.com/repos"), false), false, "wrong API host");
        System.out.println("Update URL policy checks passed.");
    }
}
