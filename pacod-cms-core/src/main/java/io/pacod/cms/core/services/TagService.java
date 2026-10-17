package io.pacod.cms.core.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.Tag;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.TagRepository;
import io.pacod.cms.core.tenant.Tenant;
import io.pacod.cms.core.tenant.TenantContext;
import io.pacod.cms.core.tenant.TenantDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TagService {

    /**
     * Matches the cache registered in the public site's {@code CacheConfig}. Declared as a
     * literal because this library must not depend on any of the applications -- and note the
     * eviction only bites in a JVM that has a cache manager, which is the public site alone.
     */
    private static final String TAGS_CACHE = "tags";

    private final TagRepository tagRepository;
    private final MongoTemplate mongoTemplate;
    private final TenantDirectory tenants;

    @Value("${app.scheduler.tags.enabled:false}")
    private boolean schedulerEnabled;

    public TagService(TagRepository tagRepository, MongoTemplate mongoTemplate, TenantDirectory tenants) {
        this.tagRepository = tagRepository;
        this.mongoTemplate = mongoTemplate;
        this.tenants = tenants;
    }

    @Scheduled(cron = "${app.scheduler.tags.cron:0 0 1 * * *}")
    public void scheduledUpdateTags() {
        if (schedulerEnabled) {
            // A scheduled run has no website bound: it visits each active one in turn.
            for (Tenant tenant : tenants.active()) {
                try {
                    log.info("Scheduled tag update started for website {}", tenant.siteId());
                    TenantContext.runAs(tenant, this::createTagsFromArticles);
                } catch (RuntimeException e) {
                    log.error("Scheduled tag update failed for website {}: {}", tenant.siteId(), e.getMessage());
                }
            }
        }
    }

    /**
     * Rebuilds the tag collection from the tags embedded in articles.
     *
     * <p>The distinct set is computed by MongoDB, the existing tags are read once, and the
     * missing ones are written in a single bulk insert. Never load the articles to read
     * {@code article.tags}: each one resolves its eager {@code @DBRef} author, category and image
     * graphs along the way.
     *
     * <p>No {@code @Transactional}: no {@code MongoTransactionManager} bean exists anywhere in
     * this project, so it would be decorative and misleading.
     */
    @CacheEvict(value = TAGS_CACHE, allEntries = true)
    public void createTagsFromArticles() {
        log.info("Start createTagsFromArticles");

        // Distinct is computed server-side instead of by loading the whole collection.
        Set<String> tagNames = mongoTemplate.findDistinct(
                        new org.springframework.data.mongodb.core.query.Query(),
                        "tags", Article.class, String.class).stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(HashSet::new));

        Set<String> existing = tagRepository.findAll().stream()
                .map(Tag::getName)
                .collect(Collectors.toSet());

        List<Tag> toCreate = tagNames.stream()
                .filter(name -> !existing.contains(name))
                .map(name -> Tag.builder().name(name).build())
                .toList();

        if (toCreate.isEmpty()) {
            log.info("createTagsFromArticles: {} distinct tags, none new", tagNames.size());
            return;
        }

        tagRepository.saveAll(toCreate);
        log.info("createTagsFromArticles: {} distinct tags, created {}", tagNames.size(), toCreate.size());
    }

    /**
     * Called on the public site's page renders, so it is cached: uncached, every one of them
     * would issue a full unfiltered {@code findAll()} against the tag collection.
     */
    // sync = true collapses a cold-start stampede: with N concurrent first requests, one
    // loads and the rest wait, instead of all N hitting MongoDB.
    @Cacheable(value = TAGS_CACHE, sync = true)
    public List<Tag> getAllTags() {
        log.debug("Loading all tags from MongoDB (cache miss)");
        return tagRepository.findAll();
    }

    public Optional<Tag> getTagById(String id) {
        return tagRepository.findById(id);
    }

    public Optional<Tag> getTagByName(String name) {
        return tagRepository.findByName(name);
    }

    @CacheEvict(value = TAGS_CACHE, allEntries = true)
    public Tag createTag(Tag tag) {
        return tagRepository.save(tag);
    }

    @CacheEvict(value = TAGS_CACHE, allEntries = true)
    public Tag updateTag(Tag tag) {
        return tagRepository.save(tag);
    }

    @CacheEvict(value = TAGS_CACHE, allEntries = true)
    public void deleteTag(String id) {
        tagRepository.deleteById(id);
    }

    @CacheEvict(value = TAGS_CACHE, allEntries = true)
    public void deleteAllTags() {
        tagRepository.deleteAll();
    }

    public boolean existsTagById(String id) {
        return tagRepository.existsById(id);
    }
}
