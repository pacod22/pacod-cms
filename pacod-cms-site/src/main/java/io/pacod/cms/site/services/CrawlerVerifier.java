package io.pacod.cms.site.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.pacod.cms.core.data.CrawlerVerification;
import io.pacod.cms.core.util.CrawlerClassifier;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Checks that a crawler comes from where its operator crawls from: reverse DNS, then forward DNS.
 *
 * <p>The procedure Google and Bing document. The address's PTR record must name a host equal to, or ending in {@code .}, one of the
 * operator's suffixes -- so {@code evil-googlebot.com} does not pass for {@code googlebot.com} --
 * and that host must resolve forward to the same address, or anyone controlling their own PTR
 * record could claim any name. See {@link CrawlerVerification} for why the outcome has three values.
 *
 * <h2>Where the address comes from matters more than the check</h2>
 * The caller passes {@code X-Real-IP}, which Caddy sets to the TCP peer it saw. Never the first
 * {@code X-Forwarded-For} entry: right behind Caddy (it replaces a header the client wrote), but
 * wrong behind any proxy that <em>appends</em> to it, and on the site's directly
 * published port: the first entry would be whatever the client sent, and a forger would put a
 * real Googlebot address there and be shown as {@code VERIFIED}. {@code X-Real-IP} is the one
 * contract that holds for every route in.
 *
 * <h2>Three rules, all on purpose</h2>
 * <ul>
 *   <li><b>Private addresses are never checked</b> and come back {@code UNKNOWN}. If the address ever
 *       turned out to be a Docker bridge or a loopback -- a proxy hop nobody noticed -- every real
 *       Googlebot would fail the PTR lookup and be labelled forged, and a false "forged" is the worst
 *       answer this screen can give.</li>
 *   <li><b>The cache key is the address <em>and</em> the suffixes</b>. Keyed by address alone, an
 *       address verified as Googlebot would stay verified for a week while claiming to be
 *       Bingbot.</li>
 *   <li><b>{@code UNKNOWN} is cached for ten minutes, not seven days.</b> It is the outcome of a
 *       timeout, and one bad minute of DNS should not mark a week of crawling unchecked.</li>
 * </ul>
 *
 * <h2>Never on the request thread</h2>
 * Lookups run on a small bounded pool; a full queue answers {@code UNKNOWN} at once rather than
 * making anyone wait. Concurrent requests from one address share one lookup. JNDI's DNS provider is
 * used rather than {@link InetAddress#getCanonicalHostName()} because it has a per-call timeout and
 * tells NXDOMAIN (a finding: {@code UNVERIFIED}) apart from a timeout (no finding: {@code UNKNOWN}),
 * where {@code getCanonicalHostName} answers both with the address itself. It needs the
 * {@code jdk.naming.dns} module in the runtime image.
 */
@Slf4j
@Component
public class CrawlerVerifier {

    private static final Duration DEFINITE_TTL = Duration.ofDays(7);
    private static final Duration UNKNOWN_TTL = Duration.ofMinutes(10);

    /** The two DNS questions, behind an interface so the tests can answer them. */
    interface DnsLookup {

        /** PTR names for the address, lowercase and without the trailing dot; empty when it has none. */
        List<String> reverse(InetAddress address) throws DnsUnavailableException;

        /** A and AAAA records for the host; empty when the name does not exist. */
        List<InetAddress> forward(String host) throws DnsUnavailableException;
    }

    /** DNS gave no answer -- a timeout, a refused query -- as opposed to answering "no such name". */
    static final class DnsUnavailableException extends Exception {

        DnsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final DnsLookup dns;
    private final boolean enabled;
    private final ThreadPoolExecutor executor;
    private final Cache<String, CrawlerVerification> cache;
    private final Map<String, CompletableFuture<CrawlerVerification>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public CrawlerVerifier(@Value("${app.crawlers.verify.enabled:true}") boolean enabled) {
        this(new JndiDnsLookup(), enabled, 4, 1000);
    }

    CrawlerVerifier(DnsLookup dns, boolean enabled, int threads, int queue) {
        this.dns = dns;
        this.enabled = enabled;
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), runnable -> {
                    Thread thread = new Thread(runnable, "crawler-dns-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        this.cache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfter(new Expiry<String, CrawlerVerification>() {
                    @Override
                    public long expireAfterCreate(String key, CrawlerVerification value, long currentTime) {
                        return (value == CrawlerVerification.UNKNOWN ? UNKNOWN_TTL : DEFINITE_TTL).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, CrawlerVerification value, long currentTime,
                                                  long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, CrawlerVerification value, long currentTime,
                                                long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /**
     * Hands {@code then} the outcome for this address and claimed identity -- at once, on this
     * thread, when nothing needs looking up; later, on a DNS thread, when something does. Never
     * throws, and {@code then} is called exactly once.
     */
    public void verify(String ip, CrawlerClassifier.Identity identity, Consumer<CrawlerVerification> then) {
        InetAddress address = publicAddress(ip);
        if (!enabled || identity == null || !identity.isVerifiable() || address == null) {
            then.accept(CrawlerVerification.UNKNOWN);
            return;
        }

        List<String> suffixes = identity.verifyHosts();
        String key = address.getHostAddress() + '|' + String.join(",", suffixes);

        CrawlerVerification cached = cache.getIfPresent(key);
        if (cached != null) {
            then.accept(cached);
            return;
        }

        CompletableFuture<CrawlerVerification> future = inFlight.get(key);
        if (future == null) {
            CompletableFuture<CrawlerVerification> created = new CompletableFuture<>();
            future = inFlight.putIfAbsent(key, created);
            if (future == null) {
                future = created;
                start(key, address, suffixes, created);
            }
        }
        future.thenAccept(then);
    }

    private void start(String key, InetAddress address, List<String> suffixes,
                       CompletableFuture<CrawlerVerification> result) {
        try {
            executor.execute(() -> {
                CrawlerVerification outcome = check(address, suffixes);
                cache.put(key, outcome);
                inFlight.remove(key, result);
                result.complete(outcome);
            });
        } catch (RejectedExecutionException e) {
            // The pool is saturated. Not cached: the next request may well find room.
            inFlight.remove(key, result);
            result.complete(CrawlerVerification.UNKNOWN);
        }
    }

    /** The lookup itself, synchronous. Package-private for the tests; never throws. */
    CrawlerVerification check(InetAddress address, List<String> suffixes) {
        try {
            List<String> names = dns.reverse(address);
            boolean anyMatch = false;

            for (String name : names) {
                if (!matchesSuffix(name, suffixes)) {
                    continue;
                }
                anyMatch = true;
                if (dns.forward(name).contains(address)) {
                    return CrawlerVerification.VERIFIED;
                }
            }
            if (!anyMatch && log.isDebugEnabled()) {
                log.debug("{} has PTR {} -- none under {}", address.getHostAddress(), names, suffixes);
            }
            return CrawlerVerification.UNVERIFIED;
        } catch (DnsUnavailableException e) {
            log.debug("DNS unavailable verifying {}: {}", address.getHostAddress(), e.getMessage());
            return CrawlerVerification.UNKNOWN;
        } catch (RuntimeException e) {
            log.warn("Crawler verification of {} failed: {}", address.getHostAddress(), e.toString());
            return CrawlerVerification.UNKNOWN;
        }
    }

    /** {@code h == suffix || h.endsWith("." + suffix)} -- so a lookalike domain never passes. */
    static boolean matchesSuffix(String host, List<String> suffixes) {
        if (host == null) {
            return false;
        }
        String normalised = host.toLowerCase(Locale.ROOT);
        if (normalised.endsWith(".")) {
            normalised = normalised.substring(0, normalised.length() - 1);
        }
        for (String suffix : suffixes) {
            if (normalised.equals(suffix) || normalised.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The address as a literal, or {@code null} when it is not one or is not publicly routable.
     * {@link InetAddress#ofLiteral} never resolves a name, so a header carrying a hostname cannot
     * make this method do DNS.
     */
    static InetAddress publicAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(ip.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return null;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            // 100.64.0.0/10, carrier-grade NAT: private in practice, though not to isSiteLocalAddress.
            if ((bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64) {
                return null;
            }
        } else if ((bytes[0] & 0xfe) == 0xfc) {
            // fc00::/7, IPv6 unique-local -- the IPv6 counterpart of 10/8, also not site-local to Java.
            return null;
        }
        return address;
    }

    /** {@code 4.3.2.1.in-addr.arpa} or the nibble-reversed {@code ….ip6.arpa}. */
    static String reverseName(InetAddress address) {
        byte[] bytes = address.getAddress();
        StringBuilder name = new StringBuilder();
        if (address instanceof Inet4Address) {
            for (int i = bytes.length - 1; i >= 0; i--) {
                name.append(bytes[i] & 0xff).append('.');
            }
            return name.append("in-addr.arpa").toString();
        }
        for (int i = bytes.length - 1; i >= 0; i--) {
            name.append(Character.forDigit(bytes[i] & 0x0f, 16)).append('.')
                    .append(Character.forDigit((bytes[i] >> 4) & 0x0f, 16)).append('.');
        }
        return name.append("ip6.arpa").toString();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** The real lookups, through JNDI's DNS provider with a short timeout. */
    static final class JndiDnsLookup implements DnsLookup {

        @Override
        public List<String> reverse(InetAddress address) throws DnsUnavailableException {
            List<String> names = new ArrayList<>();
            for (String value : query(reverseName(address), "PTR")) {
                String host = value.toLowerCase(Locale.ROOT);
                names.add(host.endsWith(".") ? host.substring(0, host.length() - 1) : host);
            }
            return names;
        }

        @Override
        public List<InetAddress> forward(String host) throws DnsUnavailableException {
            List<InetAddress> addresses = new ArrayList<>();
            for (String value : query(host, "A", "AAAA")) {
                try {
                    addresses.add(InetAddress.ofLiteral(value.strip()));
                } catch (IllegalArgumentException e) {
                    // Not an address record's shape; ignore it rather than fail the whole lookup.
                }
            }
            return addresses;
        }

        private static List<String> query(String name, String... types) throws DnsUnavailableException {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "1500");
            env.put("com.sun.jndi.dns.timeout.retries", "1");

            DirContext context = null;
            try {
                context = new InitialDirContext(env);
                Attributes attributes = context.getAttributes(name, types);
                List<String> values = new ArrayList<>();
                for (String type : types) {
                    Attribute attribute = attributes.get(type);
                    if (attribute == null) {
                        continue;
                    }
                    NamingEnumeration<?> all = attribute.getAll();
                    while (all.hasMore()) {
                        values.add(String.valueOf(all.next()));
                    }
                }
                return values;
            } catch (NameNotFoundException e) {
                // NXDOMAIN: an answer, and the answer is "nothing".
                return List.of();
            } catch (NamingException e) {
                throw new DnsUnavailableException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            } finally {
                if (context != null) {
                    try {
                        context.close();
                    } catch (NamingException ignored) {
                        // Nothing to do.
                    }
                }
            }
        }
    }
}
