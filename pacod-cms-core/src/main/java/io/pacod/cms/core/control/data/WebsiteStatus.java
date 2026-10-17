package io.pacod.cms.core.control.data;

/** Where a website is in its life. Only {@link #ACTIVE} websites answer at their hosts. */
public enum WebsiteStatus {

    /** Created; its database and storage are being prepared. */
    PROVISIONING,

    /** Serving. */
    ACTIVE,

    /** Kept, but answering nothing. */
    SUSPENDED,

    /**
     * Being deleted: answering nothing, its domains released, its pacod-id scope going. A website
     * stays here only while a step of the deletion failed -- deleting it again finishes the job.
     */
    DELETING
}
