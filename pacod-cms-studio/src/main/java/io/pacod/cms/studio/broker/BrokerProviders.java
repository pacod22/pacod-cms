package io.pacod.cms.studio.broker;

import io.pacod.cms.core.control.data.BrokerCredential;
import io.pacod.cms.core.control.repository.BrokerCredentialRepository;
import io.pacod.cms.studio.identity.Provisioner;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Each organisation's own pacod-broker provider token: read from the control plane
 * ({@link BrokerCredential}), and made the first time an organisation offers a website, by the
 * provisioner ({@link Provisioner#mintBrokerProvider}). Each organisation announcing as itself is
 * what keeps its members' reads out of its own reputation.
 *
 * <p>Against a broker in open mode no token is needed: {@link #token} answers an empty string.
 */
@Slf4j
public class BrokerProviders {

    private final BrokerCredentialRepository credentials;
    private final Supplier<Provisioner> provisioner;
    private final boolean openMode;

    public BrokerProviders(BrokerCredentialRepository credentials, Supplier<Provisioner> provisioner, boolean openMode) {
        this.credentials = credentials;
        this.provisioner = provisioner;
        this.openMode = openMode;
    }

    /**
     * The organisation's token -- stored, else made now -- or empty when none can be had: no
     * provisioner token, or pacod-id refusing (the account lacks {@code broker.provisioner}).
     */
    public Optional<String> token(String organisationId) {
        if (openMode) {
            return Optional.of("");
        }
        Optional<String> stored = credentials.findById(organisationId).map(BrokerCredential::getToken)
                .filter(t -> t != null && t.startsWith("pidt_"));
        if (stored.isPresent()) {
            return stored;
        }
        Provisioner p = provisioner.get();
        if (p == null) {
            log.warn("Organisation {} offers a website on pacod-broker, but there is no provisioner token to make its provider",
                    organisationId);
            return Optional.empty();
        }
        Provisioner.Minted minted = p.mintBrokerProvider(organisationId);
        BrokerCredential c = new BrokerCredential();
        c.setOrganisationId(organisationId);
        c.setAccountId(minted.accountId());
        c.setToken(minted.token());
        c.setCreatedOn(Instant.now());
        credentials.save(c);
        log.info("Made organisation {}'s pacod-broker provider ({})", organisationId, minted.accountId());
        return Optional.of(minted.token());
    }

    /** Forgets a token the broker refused (revoked, expired), so the next round makes a new one. */
    public void forget(String organisationId) {
        if (!openMode) {
            credentials.deleteById(organisationId);
        }
    }

    public boolean openMode() {
        return openMode;
    }
}
