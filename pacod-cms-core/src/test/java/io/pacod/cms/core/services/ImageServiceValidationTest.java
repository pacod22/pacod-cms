package io.pacod.cms.core.services;

import io.pacod.cms.core.data.ImageMedia;
import io.pacod.cms.core.exceptions.MediaException;
import io.pacod.cms.core.repository.ArticleRepository;
import io.pacod.cms.core.repository.ImageMediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Upload rules, enforced on the server.
 *
 * <p>A limit applied only in the browser is bypassed by anything posting straight at the
 * endpoint, so every case below is checked against {@code ImageService} itself.
 */
class ImageServiceValidationTest {

    @TempDir
    Path imageFolder;

    /**
     * The defaults of {@code app.image.required-width}, {@code -height} and {@code max-bytes}.
     *
     * <p>Written out here rather than read off the service, because the limits are settings, not
     * constants: a test asserting against whatever the service happens to hold would pass at every
     * value, including a misconfigured one, and could not say what the shipped default is.
     */
    private static final int DEFAULT_WIDTH = 600;
    private static final int DEFAULT_HEIGHT = 400;
    private static final long DEFAULT_MAX_BYTES = 128L * 1024L;

    private ImageMediaRepository repository;
    private ImageService service;

    @BeforeEach
    void setUp() {
        repository = mock(ImageMediaRepository.class);

        service = new ImageService();
        // ImageService uses field injection; there is no constructor to hand these to. The four
        // upload limits are @Value fields with defaults in the annotation, which Spring applies
        // and `new` does not -- so an unset test would run against 0x0 and a 0-byte cap, refusing
        // everything, rather than against what the application ships.
        ReflectionTestUtils.setField(service, "imageMediaRepository", repository);
        ReflectionTestUtils.setField(service, "imageFolder", imageFolder.toString());
        configure(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_MAX_BYTES, true, true);

        when(repository.getMaxId(any())).thenReturn(List.of());
        when(repository.findImageMediaByFilename(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(ImageMedia.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void configure(int width, int height, long maxBytes,
                           boolean dimensionCheck, boolean fileSizeCheck) {
        ReflectionTestUtils.setField(service, "requiredWidth", width);
        ReflectionTestUtils.setField(service, "requiredHeight", height);
        ReflectionTestUtils.setField(service, "fileSizeCheckEnabled", fileSizeCheck);
        // A DataSize rather than a long so that app.image.max-bytes takes "2MB" as well as a
        // plain byte count -- it sits beside spring.servlet.multipart.max-file-size=4MB in the
        // console's properties, and a long there would refuse to start on the obvious spelling.
        ReflectionTestUtils.setField(service, "maxImageBytes", DataSize.ofBytes(maxBytes));
        ReflectionTestUtils.setField(service, "dimensionCheckEnabled", dimensionCheck);
    }

    /**
     * A PNG that is genuinely over the byte cap, as opposed to a byte array that merely is.
     *
     * <p>{@link #png} draws a single flat colour, which deflates to a couple of hundred bytes at
     * any size -- so it can never test the byte limit from the accepting side. Random pixels do
     * not compress, which is what makes this big enough to matter while still being a real image
     * the dimension check can read.
     */
    private static byte[] noisyPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    @Test
    @DisplayName("an image of exactly the required size is stored")
    void acceptsACorrectlySizedImage() throws Exception {
        byte[] content = png(DEFAULT_WIDTH, DEFAULT_HEIGHT);

        ImageMedia saved = service.save(file("garden.png", "image/png", content));

        assertThat(saved).isNotNull();
        assertThat(saved.getFilename()).isEqualTo("garden.png");
        assertThat(Files.exists(imageFolder.resolve("garden.png"))).isTrue();
        verify(repository).save(any(ImageMedia.class));
    }

    @Test
    @DisplayName("the wrong dimensions are refused, and nothing is written to disk")
    void rejectsWrongDimensions() throws Exception {
        byte[] content = png(DEFAULT_WIDTH + 1, DEFAULT_HEIGHT);

        assertThatThrownBy(() -> service.save(file("wide.png", "image/png", content)))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("601x400");

        assertThat(imageFolder.toFile().list()).isEmpty();
        verify(repository, never()).save(any(ImageMedia.class));
    }

    @Test
    @DisplayName("a file over the size limit is refused")
    void rejectsAnOversizeFile() {
        byte[] content = new byte[(int) DEFAULT_MAX_BYTES + 1];

        assertThatThrownBy(() -> service.save(file("huge.png", "image/png", content)))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("a content type outside the allowed set is refused")
    void rejectsAForeignContentType() throws Exception {
        byte[] content = png(DEFAULT_WIDTH, DEFAULT_HEIGHT);

        assertThatThrownBy(() -> service.save(file("report.pdf", "application/pdf", content)))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("content type");
    }

    @Test
    @DisplayName("a non-image wearing an image content type is refused")
    void rejectsSomethingThatIsNotAnImage() {
        // ImageIO returns null rather than throwing here, which is the case the null check covers.
        byte[] content = "this is not a PNG".getBytes();

        assertThatThrownBy(() -> service.save(file("fake.png", "image/png", content)))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("not a readable image");
    }

    @Test
    @DisplayName("an empty upload is refused")
    void rejectsAnEmptyFile() {
        assertThatThrownBy(() -> service.save(file("empty.png", "image/png", new byte[0])))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("with the dimension check off, an off-size image is stored")
    void acceptsAnyDimensionsWhenTheCheckIsOff() throws Exception {
        configure(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_MAX_BYTES, false, true);

        byte[] content = png(1024, 300);

        ImageMedia saved = service.save(file("panorama.png", "image/png", content));

        assertThat(saved).isNotNull();
        assertThat(Files.exists(imageFolder.resolve("panorama.png"))).isTrue();
    }

    @Test
    @DisplayName("the dimension switch governs dimensions only -- a non-image is still refused")
    void stillRefusesANonImageWhenTheCheckIsOff() {
        // The one case that would make this setting a security hole rather than a layout one:
        // every caller of save() relies on it, so the readability check has to survive at every
        // setting.
        configure(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_MAX_BYTES, false, true);

        assertThatThrownBy(() -> service.save(file("fake.png", "image/png", "not a PNG".getBytes())))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("not a readable image");
    }

    @Test
    @DisplayName("with the check off, the byte limit and the content type still apply")
    void stillAppliesTheOtherLimitsWhenTheCheckIsOff() throws Exception {
        configure(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_MAX_BYTES, false, true);

        assertThatThrownBy(() -> service.save(
                        file("huge.png", "image/png", new byte[(int) DEFAULT_MAX_BYTES + 1])))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("limit");

        assertThatThrownBy(() -> service.save(
                        file("report.pdf", "application/pdf", png(1024, 300))))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("content type");
    }

    @Test
    @DisplayName("a configured size is the size enforced, and the refusal names it")
    void enforcesTheConfiguredSize() throws Exception {
        configure(800, 600, DEFAULT_MAX_BYTES, true, true);

        assertThat(service.save(file("wide.png", "image/png", png(800, 600)))).isNotNull();

        // And the shipped default is the one refused here, which is what proves the check reads
        // the setting rather than a constant that happens to agree with it.
        assertThatThrownBy(() -> service.save(
                        file("small.png", "image/png", png(DEFAULT_WIDTH, DEFAULT_HEIGHT))))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("600x400; the site layout requires exactly 800x600");
    }

    @Test
    @DisplayName("a configured byte limit is the limit enforced")
    void enforcesTheConfiguredByteLimit() throws Exception {
        byte[] content = png(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        configure(DEFAULT_WIDTH, DEFAULT_HEIGHT, content.length - 1L, true, true);

        assertThatThrownBy(() -> service.save(file("garden.png", "image/png", content)))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("limit is " + (content.length - 1));
    }

    /**
     * A context shaped the way {@code SpringApplication} shapes one.
     *
     * <p>The conversion service is not decoration. {@code ApplicationContextRunner} builds a plain
     * {@code AnnotationConfigApplicationContext}, which has no {@code DataSize} converter, while
     * every real application here starts through {@code SpringApplication} -- and that installs
     * {@code ApplicationConversionService} on both the environment and the bean factory. Without
     * this line the runner reports "no matching editors or conversion strategy found" for a
     * spelling the deployed console accepts, which is a test failing at a fiction.
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()));
    }

    @Test
    @DisplayName("all five settings bind from properties, and the byte cap takes 2MB as well as a count")
    void limitsBindFromProperties() {
        // The other tests set these fields by reflection, which cannot see a binding failure --
        // and a binding failure here is not a wrong number, it is a context that never starts and
        // a studio container that restart-loops. The spelling that matters is "2MB": this setting
        // is declared near spring.servlet.multipart.max-file-size=4MB, so it is the one an
        // operator will reach for, and on a plain long it would not start. The other half
        // of this, in a genuine Spring Boot context, is MgmtScreenRenderTest.NonDefaultImageRules.
        runner()
                .withBean(ImageMediaRepository.class, () -> mock(ImageMediaRepository.class))
                .withBean(ArticleRepository.class, () -> mock(ArticleRepository.class))
                .withUserConfiguration(ImageService.class)
                .withPropertyValues(
                        "app.image.folder=" + imageFolder,
                        "app.image.required-width=800",
                        "app.image.required-height=600",
                        "app.image.max-bytes=2MB",
                        "app.image.dimension-check.enabled=false",
                        "app.image.file-size-check.enabled=false")
                .run(context -> {
                    ImageService bound = context.getBean(ImageService.class);
                    assertThat(bound.getRequiredWidth()).isEqualTo(800);
                    assertThat(bound.getRequiredHeight()).isEqualTo(600);
                    assertThat(bound.getMaxImageBytes()).isEqualTo(2L * 1024 * 1024);
                    assertThat(bound.isDimensionCheckEnabled()).isFalse();
                    assertThat(bound.isFileSizeCheckEnabled()).isFalse();
                });

        // And with nothing declared, the shipped defaults are what the annotations say -- the only
        // place they are written down, so this is what pins them.
        runner()
                .withBean(ImageMediaRepository.class, () -> mock(ImageMediaRepository.class))
                .withBean(ArticleRepository.class, () -> mock(ArticleRepository.class))
                .withUserConfiguration(ImageService.class)
                .withPropertyValues("app.image.folder=" + imageFolder)
                .run(context -> {
                    ImageService bound = context.getBean(ImageService.class);
                    assertThat(bound.getRequiredWidth()).isEqualTo(DEFAULT_WIDTH);
                    assertThat(bound.getRequiredHeight()).isEqualTo(DEFAULT_HEIGHT);
                    assertThat(bound.getMaxImageBytes()).isEqualTo(DEFAULT_MAX_BYTES);
                    assertThat(bound.isDimensionCheckEnabled()).isTrue();
                    assertThat(bound.isFileSizeCheckEnabled()).isTrue();
                });
    }

    @Test
    @DisplayName("dimensions only: an off-size image is refused, an oversize one is not")
    void enforcesDimensionsWithoutTheByteCap() throws Exception {
        // One of the two mixed combinations, and the pair is the point: they are what prove there
        // are two flags rather than one flag read twice. Wanting a uniform layout is no reason to
        // refuse a heavy photograph.
        configure(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_MAX_BYTES, true, false);

        assertThatThrownBy(() -> service.save(file("wide.png", "image/png", png(1024, 300))))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("requires exactly");

        // A correctly sized PNG well past the cap: accepted, because nothing is capping it here.
        byte[] heavy = noisyPng(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        assertThat(heavy.length).isGreaterThan((int) DEFAULT_MAX_BYTES);
        assertThat(service.save(file("heavy.png", "image/png", heavy))).isNotNull();
    }

    @Test
    @DisplayName("file size only: an oversize image is refused, an off-size one is not")
    void enforcesTheByteCapWithoutTheDimensions() throws Exception {
        // The other mixed combination. Wanting to bound what a reader downloads is no reason to
        // also dictate the aspect ratio.
        configure(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_MAX_BYTES, false, true);

        assertThatThrownBy(() -> service.save(
                        file("huge.png", "image/png", new byte[(int) DEFAULT_MAX_BYTES + 1])))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("limit");

        assertThat(service.save(file("panorama.png", "image/png", png(1024, 300)))).isNotNull();
    }

    @Test
    @DisplayName("with both off, only 'is this an image' is left -- and it still refuses")
    void bothOffStillRefusesANonImage() throws Exception {
        configure(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_MAX_BYTES, false, false);

        // Neither switch may reach these two: every caller of save() relies on them, and the
        // readability check is what refuses a non-image.
        assertThatThrownBy(() -> service.save(file("fake.png", "image/png", "not a PNG".getBytes())))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("not a readable image");

        assertThatThrownBy(() -> service.save(
                        file("report.pdf", "application/pdf", png(1024, 300))))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("content type");

        assertThatThrownBy(() -> service.save(file("empty.png", "image/png", new byte[0])))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("empty");

        // And what both switches were turned off to allow now goes through.
        byte[] heavy = noisyPng(1024, 300);
        assertThat(heavy.length).isGreaterThan((int) DEFAULT_MAX_BYTES);
        assertThat(service.save(file("anything.png", "image/png", heavy))).isNotNull();
    }

    @Test
    @DisplayName("a filename that is already taken is still refused")
    void rejectsADuplicateFilename() throws Exception {
        when(repository.findImageMediaByFilename("garden.png"))
                .thenReturn(Optional.of(new ImageMedia(1)));

        byte[] content = png(DEFAULT_WIDTH, DEFAULT_HEIGHT);

        assertThatThrownBy(() -> service.save(file("garden.png", "image/png", content)))
                .isInstanceOf(MediaException.class)
                .hasMessageContaining("already exists");
    }
}
