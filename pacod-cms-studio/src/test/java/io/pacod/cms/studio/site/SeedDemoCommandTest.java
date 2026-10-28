package io.pacod.cms.studio.site;

import io.pacod.cms.core.seed.DemoContent;
import io.pacod.cms.core.seed.DirectorySeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeedDemoCommandTest {

    @TempDir
    Path dir;

    private static DefaultApplicationArguments args(String... values) {
        return new DefaultApplicationArguments(values);
    }

    @Test
    @DisplayName("--seed-dir names a folder's seed; --demo, or nothing, a shipped one")
    void namesTheSeed() {
        assertThat(SeedDemoCommand.source(args("--seed-demo", "--seed-dir=" + dir))).get()
                .isInstanceOf(DirectorySeed.class);
        assertThat(SeedDemoCommand.source(args("--seed-demo", "--demo=space"))).contains(DemoContent.Demo.SPACE);
        assertThat(SeedDemoCommand.source(args("--seed-demo"))).contains(DemoContent.Demo.JOURNAL);
        assertThat(SeedDemoCommand.source(args("--seed-demo", "--demo=moon"))).isEmpty();
    }

    @Test
    @DisplayName("two seeds, or a folder that is not there, are refused before anything runs")
    void refusesTwoSeedsOrNoFolder() {
        assertThatThrownBy(() -> SeedDemoCommand.source(args("--seed-dir=" + dir, "--demo=space")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("give one");
        assertThatThrownBy(() -> SeedDemoCommand.source(args("--seed-dir=" + dir.resolve("none"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No seed folder");
    }
}
