package io.pacod.cms.core.services;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Records article views as a single atomic {@code $inc}.
 *
 * <p>Never a read-modify-write: a {@code findById} followed by a full {@code save()} on every
 * page view would rewrite the entire document -- the complete article body plus every localized
 * title/description/text array -- on each request, and lose increments whenever two readers
 * overlap.
 *
 * <p>The update deliberately projects <em>only</em> the counter field. Returning the whole
 * document here would defeat the point of caching the article body: the expensive read
 * would happen anyway. One integer crosses the wire per view.
 */
@Slf4j
@Component
public class ArticleViewCounter {

    private static final String COLLECTION = "article";
    private static final String COUNTER_FIELD = "counter";

    private final MongoTemplate mongoTemplate;

    public ArticleViewCounter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Atomically increments the view counter and returns the new value.
     *
     * @return the counter after incrementing, or {@code null} if no such article exists
     *         or the increment could not be applied.
     */
    public Integer recordView(Integer id) {
        if (id == null) {
            return null;
        }
        try {
            Document updated = mongoTemplate.getCollection(COLLECTION).findOneAndUpdate(
                    new Document("_id", id),
                    new Document("$inc", new Document(COUNTER_FIELD, 1)),
                    new FindOneAndUpdateOptions()
                            .projection(new Document(COUNTER_FIELD, 1))
                            .returnDocument(ReturnDocument.AFTER));

            if (updated == null) {
                log.debug("No article found to increment for id={}", id);
                return null;
            }
            return readCounter(updated);
        } catch (RuntimeException e) {
            // A view counter must never be the reason a page fails to render.
            log.warn("Failed to record view for article id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Existing documents may store the counter as an int, a long or a double depending on
     * how they were written, so read it defensively rather than casting.
     */
    private Integer readCounter(Document doc) {
        Object raw = doc.get(COUNTER_FIELD);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
