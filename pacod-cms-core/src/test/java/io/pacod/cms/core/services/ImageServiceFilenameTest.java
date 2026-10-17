package io.pacod.cms.core.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** What a client-supplied file name becomes before it is a path in a website's folder and a URL. */
class ImageServiceFilenameTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "garden.jpg|garden.jpg",
            "Field Trip.jpg|Field-Trip.jpg",
            "  two  spaces .png |two-spaces-.png",
            "C:\\Users\\me\\photo.jpg|photo.jpg",
            "../../etc/passwd|passwd",
            "a/b/../c.webp|c.webp",
            "..|NULL",
            ".|NULL",
            "dir/|NULL",
    })
    @DisplayName("only the last segment survives, with whitespace as hyphens")
    void sanitises(String original, String expected) {
        assertThat(ImageService.safeFilename(original)).isEqualTo(expected);
    }
}
