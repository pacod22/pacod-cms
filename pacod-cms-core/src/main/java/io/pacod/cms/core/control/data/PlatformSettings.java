package io.pacod.cms.core.control.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The installation's own settings: who may create organisations, how many each person and each
 * organisation may have, and whether a new organisation starts with AI on. One document, id
 * {@value #ID}, in the control plane; edited on the studio's platform screen.
 *
 * <p>The limits are what keeps self-service affordable: without them any sign-up could create
 * databases without bound and spend the one shared AI key. Platform admins are not limited.
 */
@Data
@Document(collection = "platformSettings")
public class PlatformSettings {

    public static final String ID = "platform";

    @Id
    private String id = ID;

    private OrgCreation orgCreation = OrgCreation.SELF_SERVICE;

    /** How many organisations one person may create. */
    private int maxOrgsPerUser = 3;

    /** How many websites one organisation may have. */
    private int maxSitesPerOrg = 5;

    /** Whether a new organisation's websites may use the AI features. */
    private boolean aiEnabledForNewOrgs;

    private Instant updatedOn;

    private String updatedBy;
}
