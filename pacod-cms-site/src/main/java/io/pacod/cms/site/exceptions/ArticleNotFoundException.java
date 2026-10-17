package io.pacod.cms.site.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No article answers this id or slug.
 *
 * <p>Thrown only by {@code ArticleTemplateController}, which renders pages — see
 * {@link PageNotFoundException} for why that makes {@code @ResponseStatus} the right shape and
 * a {@code ResponseEntity} the wrong one.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ArticleNotFoundException extends Exception{

    public ArticleNotFoundException (Integer articleId) {
        super (String.format("Article Id=%s cannot be found", articleId));
    }

}
