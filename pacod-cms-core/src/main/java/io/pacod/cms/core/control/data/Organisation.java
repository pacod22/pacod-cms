package io.pacod.cms.core.control.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * An organisation: the owner of one or more websites, and a scope in pacod-id where roles are held
 * ({@code organisation:<id>}).
 *
 * <p>Control-plane data -- it lives in the platform database, never in a website's.
 *
 * <p><b>The id is generated, never chosen</b> ({@link #newId()}): pacod-id's organisation ids and
 * slugs are one namespace across every product it serves, so an id typed by a person could claim,
 * or be refused as, another product's organisation. What people see is {@link #name}.
 */
@Data
@Document(collection = "organisation")
public class Organisation {

    @Id
    private String id;

    private String name;

    private Instant createdOn;

    /** The pacod-id account ({@code usr_…}) that created it; null for one made by the platform. */
    private String createdBy;

    /**
     * Whether its websites may use the AI features. Off for a new organisation unless the platform
     * settings say otherwise: every call spends the platform's one gateway key.
     */
    private boolean aiEnabled;

    public Organisation() {
    }

    /** {@code org_} and 32 hex digits: unique, and safe as a pacod-id scope id and slug. */
    public static String newId() {
        return "org_" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public Organisation(String id, String name) {
        this.id = id;
        this.name = name;
        this.createdOn = Instant.now();
    }
}
