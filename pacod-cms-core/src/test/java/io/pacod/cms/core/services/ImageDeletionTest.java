package io.pacod.cms.core.services;

import io.pacod.cms.core.data.Article;
import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting an image that several articles share.
 *
 * <p>The console's image library attaches one {@code ImageMedia} to several articles, so a path
 * that deleted it outright would silently break a page nobody was editing -- and several paths
 * delete images: {@code MgmtArticleService.removeImage},
 * {@link ArticleService#deleteArticle(Article)} and the image library's own delete.
 *
 * <p>The guard is therefore in {@link ImageService#deleteImage(Integer)}, the point they all
 * share. These cases pin both halves of it: the refusal, and the ordering in
 * {@code deleteArticle} without which the refusal would fire for every article ever deleted.
 */
class ImageDeletionTest {

    @TempDir
    Path imageFolder;

    private ImageMediaRepository imageMediaRepository;
    private ArticleRepository articleRepository;
    private ImageService imageService;

    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        imageMediaRepository = mock(ImageMediaRepository.class);
        articleRepository = mock(ArticleRepository.class);

        imageService = new ImageService();
        // Field injection; there is no constructor to hand these to.
        ReflectionTestUtils.setField(imageService, "imageMediaRepository", imageMediaRepository);
        ReflectionTestUtils.setField(imageService, "articleRepository", articleRepository);
        ReflectionTestUtils.setField(imageService, "imageFolder", imageFolder.toString());

        ImageMedia image = new ImageMedia(3);
        image.setFilename("shared.jpg");
        when(imageMediaRepository.findById(3)).thenReturn(Optional.of(image));

        file = imageFolder.resolve("shared.jpg");
        Files.write(file, new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("an image another article still holds is left alone, file and record")
    void deleteIsRefusedWhileReferenced() {
        when(articleRepository.countArticleByImages(3)).thenReturn(1L);

        assertThat(imageService.deleteImage(3)).isFalse();

        // Both halves matter. A deleted file with a surviving record is a broken <img>; a deleted
        // record with a surviving file is an orphan nothing can reach.
        assertThat(file).exists();
        verify(imageMediaRepository, never()).deleteById(anyInt());
    }

    @Test
    @DisplayName("an image nothing references is deleted, file and record")
    void deleteRemovesAnUnreferencedImage() {
        when(articleRepository.countArticleByImages(3)).thenReturn(0L);

        assertThat(imageService.deleteImage(3)).isTrue();

        assertThat(file).doesNotExist();
        verify(imageMediaRepository).deleteById(3);
    }

    @Test
    @DisplayName("deleting an article removes the article first, so its own images can be cleaned")
    void deleteArticleRemovesTheArticleBeforeItsImages() {
        ArticleService articleService = new ArticleService();
        ReflectionTestUtils.setField(articleService, "articleRepository", articleRepository);
        ReflectionTestUtils.setField(articleService, "imageService", imageService);

        Article article = new Article(42);
        article.setImages(new ArrayList<>(List.of(imageMediaRepository.findById(3).orElseThrow())));

        // Zero, because by the time the images are cleaned the article holding them is gone. This
        // is exactly what the ordering buys: cleaning first would count the article being deleted
        // as a reference, the guard would refuse, and every deleted article would leave its files
        // behind for good.
        when(articleRepository.countArticleByImages(3)).thenReturn(0L);

        articleService.deleteArticle(article);

        InOrder order = inOrder(articleRepository, imageMediaRepository);
        order.verify(articleRepository).deleteById(42);
        order.verify(imageMediaRepository).deleteById(3);
        assertThat(file).doesNotExist();
    }
}
