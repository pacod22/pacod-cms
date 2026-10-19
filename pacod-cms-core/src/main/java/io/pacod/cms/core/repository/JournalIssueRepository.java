package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.IssueStatus;
import io.pacod.cms.core.data.JournalIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface JournalIssueRepository extends MongoRepository<JournalIssue, Integer> {

    /*
     * ---------------------------------------------------------------- the public site's reads
     *
     * A reader sees PUBLISHED issues only, as with articles. The one exception is the issue's
     * print front page, which reads by plain findById -- and answers only behind a valid print
     * token (IssuePrintController), because the studio renders drafts before they are published.
     */

    @Query(value = "{status: 'PUBLISHED'}", sort = "{ year: -1, month: -1, volume: -1, number: -1 }")
    Page<JournalIssue> findPublished(Pageable pageable);

    @Query("{ _id: ?0, status: 'PUBLISHED' }")
    Optional<JournalIssue> findPublishedById(Integer id);

    boolean existsByStatus(IssueStatus status);

    @Query(value = "{status: 'PUBLISHED'}", fields = "{ '_id': 1, 'lastModifiedOn': 1, 'publishedOn': 1 }")
    List<JournalIssue> findPublishedForSitemap();

    /* ---------------------------------------------------------------- the studio's (drafts included) */

    @Query(value = "{}", fields = "{ '_id' : 1}", sort = "{ _id : -1 }")
    List<JournalIssue> getMaxId(Pageable pageable);

    @Query(value = "{}", sort = "{ year: -1, month: -1, volume: -1, number: -1 }")
    Page<JournalIssue> findAllIssues(Pageable pageable);
}
