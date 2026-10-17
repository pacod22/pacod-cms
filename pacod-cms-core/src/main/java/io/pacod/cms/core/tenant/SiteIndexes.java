package io.pacod.cms.core.tenant;

import io.pacod.cms.core.config.MongoConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver.IndexDefinitionHolder;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;

/**
 * Creates the indexes the documents declare -- a website's in its own database, the platform's in
 * the control plane.
 *
 * <p>Index creation by Spring Data stays off ({@code spring.data.mongodb.auto-index-creation=false}):
 * it runs during context refresh and lets one failure abort startup, and it would build every
 * index in whichever database the factory happened to answer with. Here each definition is
 * attempted on its own and a failure is logged at ERROR -- which means that index does not exist:
 * its queries are collection scans, and a failed unique index enforces nothing.
 *
 * <p>The two sets are split by package, so control-plane collections never appear in a website's
 * database and website collections never in the control plane.
 */
@Slf4j
@Component
public class SiteIndexes {

    private static final String CONTROL_PACKAGE = "io.pacod.cms.core.control.";

    private final MongoOperations siteOperations;
    private final MongoOperations controlPlaneOperations;
    private final MongoMappingContext mappingContext;

    public SiteIndexes(MongoOperations siteOperations,
                       @Qualifier(MongoConfig.CONTROL_PLANE_TEMPLATE) MongoOperations controlPlaneOperations,
                       MongoMappingContext mappingContext) {
        this.siteOperations = siteOperations;
        this.controlPlaneOperations = controlPlaneOperations;
        this.mappingContext = mappingContext;
    }

    /** The bound website's indexes, in its own database. Requires a {@link TenantContext}. */
    public Result ensureSiteIndexes() {
        String site = TenantContext.current().siteId();
        return ensure(siteOperations, entity -> !entity.getType().getName().startsWith(CONTROL_PACKAGE),
                "website " + site);
    }

    /** The platform's own indexes, in the control-plane database. */
    public Result ensureControlPlaneIndexes() {
        return ensure(controlPlaneOperations, entity -> entity.getType().getName().startsWith(CONTROL_PACKAGE),
                "control plane");
    }

    private Result ensure(MongoOperations operations, Predicate<MongoPersistentEntity<?>> include, String where) {
        MongoPersistentEntityIndexResolver resolver = new MongoPersistentEntityIndexResolver(mappingContext);
        int created = 0;
        int failed = 0;
        for (MongoPersistentEntity<?> entity : mappingContext.getPersistentEntities()) {
            // Nested types the mapping context has seen are not collections of their own.
            if (!entity.isAnnotationPresent(Document.class) || !include.test(entity)) {
                continue;
            }
            for (IndexDefinitionHolder definition : resolver.resolveIndexForEntity(entity)) {
                try {
                    operations.indexOps(definition.getCollection()).createIndex(definition);
                    created++;
                } catch (DataAccessResourceFailureException e) {
                    log.error("MongoDB unreachable; index creation for the {} abandoned after {} index(es): {}",
                            where, created, e.getMessage());
                    return new Result(created, failed + 1);
                } catch (DataAccessException e) {
                    failed++;
                    log.error("Failed to create index {} on '{}' for the {} -- the queries it backs will be "
                                    + "collection scans until this is fixed: {}",
                            describe(definition), definition.getCollection(), where, e.getMessage());
                }
            }
        }
        if (failed > 0) {
            log.warn("Indexes for the {}: {} created, {} FAILED", where, created, failed);
        } else {
            log.info("Indexes for the {}: {} created", where, created);
        }
        return new Result(created, failed);
    }

    private static String describe(IndexDefinitionHolder definition) {
        String name = definition.getIndexOptions().getString("name");
        return name != null ? name : definition.getIndexKeys().toJson();
    }

    /** How many index definitions were applied, and how many failed. */
    public record Result(int created, int failed) {
        public boolean ok() {
            return failed == 0;
        }
    }
}
