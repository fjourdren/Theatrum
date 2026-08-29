package com.fjourdren.theatrum.infrastructure.adapter.out.config;

import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configs shipped to users — {@code config.yml.example} and {@code examples/*.yml} — must load
 * through the real loader, not merely parse as YAML.
 *
 * <p>They are documentation people copy verbatim, so a validation rule tightened in
 * {@link YamlConfigFile} has to fail here rather than in a user's first run. Nothing else covers
 * them: {@code ConfigLoadingTest} and {@link YamlConfigFileTest} both build their YAML inline.
 *
 * <p>Assert on shape only — channels exist, ports are positive, types resolve. The contents of a
 * shipped config are documentation and change freely; a test that pins one of its values breaks
 * on an edit that was never a regression.
 */
class ShippedConfigsTest {

    /** Surefire sets {@code basedir}; the fallback keeps the test runnable from an IDE. */
    private static final Path ROOT = Path.of(System.getProperty("basedir", "."));

    static Stream<Path> shippedConfigs() throws IOException {
        try (var examples = Files.list(ROOT.resolve("examples"))) {
            var configs = Stream.concat(
                            Stream.of(ROOT.resolve("config.yml.example")),
                            examples.filter(p -> p.toString().endsWith(".yml")))
                    .sorted()
                    .toList();

            // A silently empty glob would make this test pass while checking nothing.
            assertThat(configs).hasSizeGreaterThan(1);
            return configs.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shippedConfigs")
    void loadsThroughTheRealLoader(Path config) {
        LoadedConfiguration loaded = new YamlConfigFile().load(config);

        assertThat(loaded.channels()).as("channels in %s", config.getFileName()).isNotEmpty();
        assertThat(loaded.server().httpPort()).isPositive();
        assertThat(loaded.channels().values())
                .allSatisfy(stream -> assertThat(stream.type()).isNotNull());
    }

    /** The two ports share a config format on purpose; drift here is a decision, not an accident. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("shippedConfigs")
    void declaresAtLeastOneUsableChannelPath(Path config) {
        LoadedConfiguration loaded = new YamlConfigFile().load(config);

        List<String> patterns = List.copyOf(loaded.channels().keySet());
        assertThat(patterns).allSatisfy(pattern -> assertThat(pattern).startsWith("/"));
    }
}
