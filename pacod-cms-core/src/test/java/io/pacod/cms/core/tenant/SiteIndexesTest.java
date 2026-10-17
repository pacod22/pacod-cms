package io.pacod.cms.core.tenant;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ImageMedia;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The point of {@link SiteIndexes} is that one failure does not stop the rest, so that is what
 * these assert -- run for the website the module's test extension binds. No MongoDB server is involved: the mapping context is built by hand and
 * {@code createIndex} is stubbed to fail.
 */
class SiteIndexesTest {

    private MongoOperations mongoOperations;
    private IndexOperations imageIndexOps;
    private IndexOperations articleIndexOps;
    private SiteIndexes indexes;

    @BeforeEach
    void setUp() {
        MongoMappingContext mappingContext = new MongoMappingContext();
        mappingContext.setSimpleTypeHolder(new MongoCustomConversions(List.of()).getSimpleTypeHolder());
        mappingContext.setInitialEntitySet(Set.of(ImageMedia.class, Article.class));
        mappingContext.afterPropertiesSet();

        mongoOperations = mock(MongoOperations.class);
        imageIndexOps = mock(IndexOperations.class);
        articleIndexOps = mock(IndexOperations.class);
        when(mongoOperations.indexOps("image")).thenReturn(imageIndexOps);
        when(mongoOperations.indexOps("article")).thenReturn(articleIndexOps);

        indexes = new SiteIndexes(mongoOperations, mock(MongoOperations.class), mappingContext);
    }

    @Test
    @DisplayName("a duplicate-key failure on one index does not stop the others being created")
    void duplicateKeyFailureIsSwallowedAndTheRestStillRun() {
        // The realistic failure: image.filename is @Indexed(unique = true) over a collection
        // that already contains two documents with the same filename.
        when(imageIndexOps.createIndex(any(IndexDefinition.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error ... index: filename"));

        assertThatCode(() -> indexes.ensureSiteIndexes()).doesNotThrowAnyException();

        // Article declares three @CompoundIndexes plus two @Indexed properties; the exact count
        // is not the point, only that the failing collection did not prevent them.
        verify(articleIndexOps, atLeastOnce()).createIndex(any(IndexDefinition.class));
    }

    @Test
    @DisplayName("an unreachable server abandons the run instead of failing every index in turn")
    void unreachableServerStopsAfterTheFirstFailure() {
        when(imageIndexOps.createIndex(any(IndexDefinition.class)))
                .thenThrow(new DataAccessResourceFailureException("Timed out while waiting for a server"));
        when(articleIndexOps.createIndex(any(IndexDefinition.class)))
                .thenThrow(new DataAccessResourceFailureException("Timed out while waiting for a server"));

        assertThatCode(() -> indexes.ensureSiteIndexes()).doesNotThrowAnyException();

        // Whichever collection was reached first, the other is never attempted.
        boolean imageAttempted = org.mockito.Mockito.mockingDetails(imageIndexOps).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("createIndex"));
        if (imageAttempted) {
            verify(articleIndexOps, never()).createIndex(any(IndexDefinition.class));
        } else {
            verify(imageIndexOps, never()).createIndex(any(IndexDefinition.class));
        }
    }
}
