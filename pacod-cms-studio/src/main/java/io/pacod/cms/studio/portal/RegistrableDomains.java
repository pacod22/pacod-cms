package io.pacod.cms.studio.portal;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;

import java.util.Locale;

/**
 * A host's registrable domain -- the public suffix plus one label, {@code example.com} for
 * {@code studio.example.com} -- which is how far a browser lets one host's cookies reach.
 *
 * <p>From the Public Suffix List as Guava ships it, <b>private section included</b>: a browser
 * treats {@code github.io} as a suffix, so {@code a.github.io} and {@code b.github.io} are two
 * registrable domains, and that is how a shared tenant domain isolates its websites from each other
 * (and from a studio beside them) once it is registered there.
 *
 * <p>A name under no listed suffix -- {@code studio.localhost}, {@code sites.internal} -- gets the
 * list's default rule: its last label is the suffix. So in development {@code studio.localhost} and
 * {@code demo.localhost} are two registrable domains, as browsers treat them.
 */
public final class RegistrableDomains {

    private RegistrableDomains() {
    }

    /** The registrable domain, or null for an IP literal or anything that is not a host name. */
    public static String of(String host) {
        InternetDomainName name = parse(host);
        if (name == null) {
            return null;
        }
        if (name.isUnderPublicSuffix()) {
            return name.topPrivateDomain().toString();
        }
        if (name.isPublicSuffix() || name.parts().size() == 1) {
            // A suffix registers nothing; no cookie can be set for it, so it is its own.
            return name.toString();
        }
        // No listed suffix: the default rule, "*" -- the last label is the suffix.
        return name.parts().get(name.parts().size() - 2) + "." + name.parts().getLast();
    }

    /**
     * Whether the name is itself a public suffix, so that every name directly below it is a
     * registrable domain of its own: {@code github.io}, {@code co.uk}, and by the default rule a
     * single label such as {@code localhost}.
     */
    public static boolean isPublicSuffix(String host) {
        InternetDomainName name = parse(host);
        return name != null && (name.isPublicSuffix() || !name.hasPublicSuffix() && name.parts().size() == 1);
    }

    private static InternetDomainName parse(String host) {
        if (host == null) {
            return null;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty() || h.startsWith("[") || InetAddresses.isInetAddress(h) || !InternetDomainName.isValid(h)) {
            return null;
        }
        return InternetDomainName.from(h);
    }
}
