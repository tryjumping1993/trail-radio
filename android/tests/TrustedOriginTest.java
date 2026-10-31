package com.trailradio.app;

public final class TrustedOriginTest {
    private static void check(String origin, boolean expected) {
        if (TrustedOrigin.matches(origin) != expected) throw new AssertionError("Unexpected origin decision: " + origin);
    }
    public static void main(String[] args) {
        check("https://trailradio.local", true);
        check("https://trailradio.local/", true);
        check("https://TRAILRADIO.local:443/", true);
        check("http://trailradio.local/", false);
        check("https://trailradio.local:444/", false);
        check("https://trailradio.local.attacker.test/", false);
        check("https://attacker@trailradio.local/", false);
        check("https://trailradio.local/other", false);
        check("https://trailradio.local/?q=1", false);
        check(null, false);
        System.out.println("Trusted origin checks passed.");
    }
}
