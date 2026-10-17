package io.pacod.cms.core.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ArticleStatus;
import io.pacod.cms.core.data.Author;
import io.pacod.cms.core.repository.AuthorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AuthorService {

    /** Limits the max-id lookup to a single document. */
    private static final Pageable TOP_ONE = PageRequest.of(0, 1);
    @Autowired
    private AuthorRepository authorRepository;

    /** Reads one document off the index instead of loading every author's id. */
    public Integer getNextId() {
        Integer id = authorRepository.getMaxId(TOP_ONE)
                .stream()
                .findFirst()
                .map(a -> a.getId() + 1)
                .orElse(1);
        log.info("Next author id: {}", id);
        return id;
    }


    public Author createAuthor(Author author) {
        log.info("Start createAuthor");
        Integer id = getNextId();

        // Create an author only when the caller passed none; whatever it passed keeps its fields
        // and gets the new id.
        if (author == null) {
            author = new Author(id);
        }

        author.setId(id);
        if(author.getTitle() == null) author.setTitle("");
        if(author.getFirstname() == null) author.setFirstname("");
        if(author.getLastname() == null) author.setLastname("");

        authorRepository.save(author);

        return author;
    }
}
