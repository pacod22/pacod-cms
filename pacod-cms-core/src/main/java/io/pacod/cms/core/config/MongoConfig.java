package io.pacod.cms.core.config;

import com.mongodb.client.MongoClient;
import io.pacod.cms.core.tenant.RoutingMongoDatabaseFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Two kinds of data, two templates.
 *
 * <ul>
 *   <li><b>Website content</b> -- every repository under {@code core.repository} and every
 *       {@code MongoTemplate} injected by type -- goes through the {@code @Primary}
 *       {@link RoutingMongoDatabaseFactory}, so it lands in the database of the website bound in
 *       {@code TenantContext}, and nowhere at all when none is.</li>
 *   <li><b>The platform's own records</b> -- organisations, websites, domains, jobs, settings --
 *       live in the control-plane database ({@code spring.mongodb.database}) and are reached only
 *       through {@code controlPlaneMongoTemplate}, by the repositories under
 *       {@code core.control.repository}.</li>
 * </ul>
 *
 * <h2>How the routing factory gets in</h2>
 * Boot's own {@code mongoDatabaseFactory} bean is <b>replaced after creation</b> by
 * {@link #routingMongoDatabaseFactory}, rather than a factory being declared here. Declaring one is
 * the obvious move and does not work: {@code MongoAutoConfiguration} backs off from creating the
 * {@code MongoClient} itself whenever any {@code MongoDatabaseFactory} bean exists, so the context
 * fails to start for want of a client. Replacing the instance keeps Boot's client, its settings and
 * its connection details, and leaves exactly one factory bean -- which is also what every consumer
 * that takes one by type gets, the auto-configured converter's {@code @DBRef} resolver included.
 *
 * <p>The primary template is declared here, which makes Boot's back off. The control-plane factory
 * is deliberately <em>not</em> a bean: as one it would compete for injection with the routing
 * factory, and a component that took the wrong one by type would write website content into the
 * platform database.
 *
 * <p>{@code @EntityScan} fixes the set of mapped documents up front, so index creation does not
 * depend on which documents happen to have a repository.
 */
@Configuration
@EntityScan(basePackages = {"io.pacod.cms.core.data", "io.pacod.cms.core.control.data", "io.pacod.cms.core.design"})
@EnableMongoRepositories(basePackages = "io.pacod.cms.core.control.repository",
        mongoTemplateRef = MongoConfig.CONTROL_PLANE_TEMPLATE)
public class MongoConfig {

    public static final String CONTROL_PLANE_TEMPLATE = "controlPlaneMongoTemplate";

    /** Boot's factory bean, as the auto-configuration names it. */
    static final String FACTORY_BEAN = "mongoDatabaseFactory";

    @Bean
    static BeanPostProcessor routingMongoDatabaseFactory(ObjectProvider<MongoClient> client, Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (FACTORY_BEAN.equals(beanName) && bean instanceof MongoDatabaseFactory
                        && !(bean instanceof RoutingMongoDatabaseFactory)) {
                    return new RoutingMongoDatabaseFactory(client.getObject(),
                            environment.getProperty("spring.mongodb.database", "pacod_cms"));
                }
                return bean;
            }
        };
    }

    @Bean
    @Primary
    public MongoTemplate mongoTemplate(MongoDatabaseFactory factory, MongoConverter converter) {
        return new MongoTemplate(factory, converter);
    }

    @Bean(name = CONTROL_PLANE_TEMPLATE)
    public MongoTemplate controlPlaneMongoTemplate(MongoClient client,
                                                   @Value("${spring.mongodb.database:pacod_cms}") String controlDatabase,
                                                   MongoMappingContext mappingContext,
                                                   MongoCustomConversions conversions) {
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, controlDatabase);
        MappingMongoConverter converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), mappingContext);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return new MongoTemplate(factory, converter);
    }
}
