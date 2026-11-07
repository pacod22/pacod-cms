package io.pacod.cms.core.control.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * How often the studio rebuilds its pacod-broker announcements, set on the studio's platform
 * screen; while {@link #refreshSeconds} is set it replaces {@code pacod.broker.refresh}
 * ({@code PACOD_BROKER_REFRESH}). One document, id {@value #ID}, in the control plane.
 *
 * <p>Apart from {@link PlatformSettings} on purpose: that one is saved whole from its own form,
 * and saving the interval must not copy the portal's starting values into storage.
 */
@Data
@Document(collection = "brokerSettings")
public class BrokerSettings {

    public static final String ID = "broker";

    @Id
    private String id = ID;

    /** Seconds between two rebuilds; null = the environment's. */
    private Integer refreshSeconds;

    private Instant updatedOn;

    private String updatedBy;
}
