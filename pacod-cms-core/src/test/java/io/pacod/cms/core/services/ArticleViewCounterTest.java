package io.pacod.cms.core.services;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArticleViewCounterTest {

    private MongoTemplate mongoTemplate;
    private MongoCollection<Document> collection;
    private ArticleViewCounter counter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        collection = mock(MongoCollection.class);
        when(mongoTemplate.getCollection("article")).thenReturn(collection);
        counter = new ArticleViewCounter(mongoTemplate);
    }

    @Test
    @DisplayName("issues an atomic $inc and returns the post-increment value")
    void incrementsAtomicallyAndReturnsNewValue() {
        when(collection.findOneAndUpdate(any(Document.class), any(Document.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("counter", 43));

        Integer result = counter.recordView(7);

        assertThat(result).isEqualTo(43);

        ArgumentCaptor<Document> filter = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<Document> update = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<FindOneAndUpdateOptions> options = ArgumentCaptor.forClass(FindOneAndUpdateOptions.class);
        verify(collection).findOneAndUpdate(filter.capture(), update.capture(), options.capture());

        assertThat(filter.getValue().get("_id")).isEqualTo(7);

        // A single atomic $inc -- not a read, modify and full save.
        assertThat(update.getValue().get("$inc")).isEqualTo(new Document("counter", 1));

        // Must return the value AFTER incrementing, or the page would render a stale count.
        assertThat(options.getValue().getReturnDocument()).isEqualTo(ReturnDocument.AFTER);
    }

    @Test
    @DisplayName("projects only the counter, so caching the article body actually pays")
    void projectsOnlyTheCounterField() {
        when(collection.findOneAndUpdate(any(Document.class), any(Document.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("counter", 1));

        counter.recordView(7);

        ArgumentCaptor<FindOneAndUpdateOptions> options = ArgumentCaptor.forClass(FindOneAndUpdateOptions.class);
        verify(collection).findOneAndUpdate(any(Document.class), any(Document.class), options.capture());

        // Without this projection the whole document comes back on every view, which would
        // defeat the article cache entirely -- the expensive read would happen anyway.
        assertThat(options.getValue().getProjection()).isEqualTo(new Document("counter", 1));
    }

    @Test
    @DisplayName("returns null for an unknown article rather than throwing")
    void unknownArticleReturnsNull() {
        when(collection.findOneAndUpdate(any(Document.class), any(Document.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        assertThat(counter.recordView(404)).isNull();
    }

    @Test
    @DisplayName("a null id short-circuits without touching MongoDB")
    void nullIdDoesNotHitMongo() {
        assertThat(counter.recordView(null)).isNull();
        verifyNoInteractions(collection);
    }

    @Test
    @DisplayName("a MongoDB failure never propagates -- a view counter must not break a page")
    void mongoFailureIsSwallowed() {
        when(collection.findOneAndUpdate(any(Document.class), any(Document.class), any(FindOneAndUpdateOptions.class)))
                .thenThrow(new RuntimeException("connection reset"));

        assertThat(counter.recordView(7)).isNull();
    }

    @Test
    @DisplayName("reads the counter defensively when stored as a long or double")
    void readsNonIntegerCounterTypes() {
        when(collection.findOneAndUpdate(any(Document.class), any(Document.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("counter", 9_000_000_000L));
        assertThat(counter.recordView(1)).isEqualTo((int) 9_000_000_000L);

        when(collection.findOneAndUpdate(any(Document.class), any(Document.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("counter", 12.0d));
        assertThat(counter.recordView(1)).isEqualTo(12);
    }
}
