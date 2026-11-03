package io.pacod.cms.core.util;

import java.net.InetAddress;

/**
 * One spelling for an IP address, shared by the application that stores one and the one that looks
 * it up.
 *
 * <p>The site writes a reader's or a crawler's address; the studio filters its logs by an address
 * an operator typed or clicked. {@code 2001:db8::1}, {@code 2001:DB8:0:0:0:0:0:1} and
 * {@code [2001:db8::1]} are one address, and an exact match between two spellings of it finds
 * nothing -- at 200, with an empty table. Both sides therefore go through {@link #normalise}.
 */
public final class IpLiterals {

    private IpLiterals() {
    }

    /**
     * The value as {@link InetAddress#getHostAddress()} spells it, or {@code null} when it is not an
     * IP literal. {@link InetAddress#ofLiteral} never resolves a name, so a host name -- or any other
     * text a header or a query string carried -- is {@code null}, never a DNS lookup.
     */
    public static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(value.strip()).getHostAddress();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
