package io.pacod.cms.core.control.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * A website: one isolated set of content -- its own MongoDB database ({@link #dbName}), its own
 * storage folder, its own cache entries -- answering at one or more {@link Domain}s.
 *
 * <p>The id is chosen once and never changes: it is in the database name, in the storage path and
 * in every studio URL, so renaming it would orphan all three. What a reader sees is {@link #name}.
 */
@Data
@Document(collection = "website")
public class Website {

    /**
     * Lowercase letters, digits and hyphens, starting with a letter: safe as a MongoDB database
     * name suffix, a directory name and a URL segment, and short enough for all three.
     */
    public static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,39}");

    @Id
    private String id;

    @Indexed(name = "website_organisationId")
    private String organisationId;

    private String name;

    private String dbName;

    /** The language the website's content is written in. */
    private String defaultLanguage = "en";

    /** An IANA zone id, e.g. {@code Europe/Berlin}. */
    private String timezone = "UTC";

    /**
     * The template set its public pages render with; null means {@code Tenant.DEFAULT_TEMPLATE_SET}.
     * A set the installation does not have renders with the default too.
     */
    private String templateSet;

    private WebsiteStatus status = WebsiteStatus.PROVISIONING;

    /** The site-database layout last applied by the provisioner; see {@code SiteProvisioner}. */
    private int schemaVersion;

    /**
     * The website's own AI switch, below its organisation's ({@code Organisation.aiEnabled}, a
     * platform admin's): set, nothing on this website calls the gateway. "Disabled" so that a
     * document without the field reads as allowed.
     */
    private boolean aiDisabled;

    private Instant createdOn;

    /** The pacod-id account ({@code usr_…}) that created it; null for one made by the platform. */
    private String createdBy;

    public Website() {
    }

    public Website(String id, String organisationId, String name) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a website id: " + id);
        }
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.dbName = dbNameFor(id);
        this.createdOn = Instant.now();
    }

    /** The database a website with this id keeps its content in. */
    public static String dbNameFor(String id) {
        return "site_" + id;
    }
}
