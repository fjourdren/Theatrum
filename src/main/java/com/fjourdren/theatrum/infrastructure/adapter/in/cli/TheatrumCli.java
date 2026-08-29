package com.fjourdren.theatrum.infrastructure.adapter.in.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;

@Command(
        name = "theatrum",
        version = "2.0",
        description = "Streaming server: VOD, HLS, DASH, live RTMP and restreaming.",
        mixinStandardHelpOptions = true)
public class TheatrumCli {

    @Option(names = {"-c", "--config"}, paramLabel = "FILE", description = "Configuration file (default: ${DEFAULT-VALUE}).")
    private Path config = Path.of("config.yml");

    public Path configPath() {
        return config;
    }
}
