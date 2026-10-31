package io.pacod.cms.core.control.data;

import lombok.Data;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The installation's pacod-gw address and key, set on the studio's platform screen or through its
 * internal API; while this document exists it replaces {@code pacod.ai.gateway-url} and
 * {@code pacod.ai.api-key} -- as a pair, so a key never goes to an address it was not given for.
 * One document, id {@value #ID}, in the control plane.
 *
 * <p>Apart from {@link PlatformSettings} on purpose: that one is read and rendered whole, and the
 * key must never be shown or answered back, only its last characters.
 */
@Data
@Document(collection = "aiGatewaySettings")
public class AiGatewaySettings {

    public static final String ID = "gateway";

    @Id
    private String id = ID;

    private String url;

    @ToString.Exclude
    private String apiKey;

    private Instant updatedOn;

    private String updatedBy;
}
