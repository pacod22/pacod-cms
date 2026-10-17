package io.pacod.cms.studio.portal;

import io.pacod.cms.core.control.data.Domain;
import io.pacod.cms.studio.MgmtOperationException;
import io.pacod.cms.studio.identity.IdProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which host names a website may claim.
 *
 * <p>A host is a name, not an address: lowercase letters, digits and hyphens in labels of up to 63
 * characters, at least two labels, no IP literal. And <b>never the studio's or pacod-id's own host,
 * nor any name below them</b>: a website answering at {@code studio.example.com} would be served
 * the studio's cookies and could post to it as itself. <b>Nor any name on their registrable
 * domain</b> ({@link RegistrableDomains}): a website at {@code www.example.com} beside
 * {@code studio.example.com} could set cookies for {@code example.com} that the studio receives.
 * In development {@code studio.localhost} and {@code demo.localhost} are two registrable domains,
 * so a sibling there is fine.
 */
@Component
public class DomainRules {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final int MAX = 253;

    private final Set<String> reserved = new LinkedHashSet<>();
    private final Set<String> reservedSites = new LinkedHashSet<>();

    public DomainRules(IdProperties id) {
        addHostOf(id.studioOrigin());
        addHostOf(id.issuer());
        for (String host : reserved) {
            String registrable = RegistrableDomains.of(host);
            if (registrable != null) {
                reservedSites.add(registrable);
            }
        }
    }

    private void addHostOf(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            String host = URI.create(url).getHost();
            if (host != null) {
                reserved.add(Domain.normalise(host));
            }
        } catch (IllegalArgumentException ignored) {
            // Not a URL; nothing to reserve.
        }
    }

    /** The host as stored, or a refusal naming why. */
    public String check(String host) {
        String h = host == null ? "" : host.strip().toLowerCase(java.util.Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (!isName(h)) {
            throw new MgmtOperationException("portal.domain.invalid", host == null ? "" : host.strip());
        }
        for (String r : reserved) {
            if (h.equals(r) || h.endsWith("." + r)) {
                throw new MgmtOperationException("portal.domain.reserved", h);
            }
        }
        String registrable = RegistrableDomains.of(h);
        if (registrable != null && reservedSites.contains(registrable)) {
            throw new MgmtOperationException("portal.domain.sameSite", h, registrable);
        }
        return h;
    }

    static boolean isName(String host) {
        if (host.isEmpty() || host.length() > MAX) {
            return false;
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        // 192.168.1.10 passes the label rule; a top-level label is never all digits.
        return !labels[labels.length - 1].chars().allMatch(Character::isDigit);
    }
}
