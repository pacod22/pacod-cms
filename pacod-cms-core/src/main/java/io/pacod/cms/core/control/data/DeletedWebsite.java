package io.pacod.cms.core.control.data;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A website deleted a moment ago, kept so its database is dropped once more after every cache
 * that still knew it has expired.
 *
 * <p>MongoDB creates a database on its first write. The public site keeps a host-to-website lookup
 * for up to a minute and buffers counts for a flush, so for a short while after the studio drops a
 * website's database the site can still write into it -- and recreate it. A tombstone is what lets
 * the studio drop it again later, and what refuses a new website the same id meanwhile. It never
 * names a database the studio did not itself delete.
 */
@Data
@Document(collection = "deletedWebsite")
public class DeletedWebsite {

    @Id
    private String id;

    private String dbName;

    private String organisationId;

    private Instant deletedOn;

    private String deletedBy;

    public DeletedWebsite() {
    }

    public DeletedWebsite(Website website, String deletedBy) {
        this.id = website.getId();
        this.dbName = website.getDbName();
        this.organisationId = website.getOrganisationId();
        this.deletedOn = Instant.now();
        this.deletedBy = deletedBy;
    }
}
