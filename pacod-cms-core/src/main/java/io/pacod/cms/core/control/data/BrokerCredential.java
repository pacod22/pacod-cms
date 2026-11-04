package io.pacod.cms.core.control.data;

import lombok.Data;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * An organisation's own pacod-broker provider: the pacod-id service account the studio made in
 * that organisation ({@code cms-broker}, holding {@code broker.provider} there) and its
 * {@code pidt_} token, with which the studio announces the organisation's offered websites. One
 * per organisation, id = the organisation's id, in the control plane.
 *
 * <p>Each organisation announces as itself so that its own members' reads never count towards
 * its reputation on the broker. The token is never rendered or answered; it is stored unencrypted,
 * like the gateway key ({@code docs/SECURITY.md}).
 */
@Data
@Document(collection = "brokerCredentials")
public class BrokerCredential {

    @Id
    private String organisationId;

    /** The service account at pacod-id ({@code svc_…}). */
    private String accountId;

    @ToString.Exclude
    private String token;

    private Instant createdOn;
}
