package io.pacod.cms.core.tenant;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

/**
 * Hands every operation the database of the website bound in {@link TenantContext}.
 *
 * <p>{@code MongoTemplate} asks its factory for the database on every operation -- repositories
 * included, and {@code @DBRef} resolution too, since a reference written without a database name
 * resolves against the factory's default -- so overriding this one method routes all of them.
 * Nothing in the application caches a {@code MongoDatabase}.
 *
 * <p>With no website bound it throws ({@link TenantContext.NoTenantException}). It never falls
 * back to the database it was constructed with: that is the platform's control-plane database,
 * and content written there would belong to no website and be visible to code reading as any.
 */
public class RoutingMongoDatabaseFactory extends SimpleMongoClientDatabaseFactory {

    public RoutingMongoDatabaseFactory(MongoClient client, String controlPlaneDatabase) {
        super(client, controlPlaneDatabase);
    }

    @Override
    public MongoDatabase getMongoDatabase() throws DataAccessException {
        return getMongoDatabase(TenantContext.current().dbName());
    }
}
