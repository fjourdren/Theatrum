package com.fjourdren.theatrum;

import com.fjourdren.theatrum.application.port.out.exception.ConfigurationException;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.infrastructure.adapter.in.cli.TheatrumCli;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.YamlConfigFile;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine;

import java.util.Map;

@SpringBootApplication
public class TheatrumApplication {

    public static void main(String[] args) {
        TheatrumCli cli = new TheatrumCli();
        CommandLine commandLine = new CommandLine(cli);

        try {
            CommandLine.ParseResult parsed = commandLine.parseArgs(args);
            if (CommandLine.printHelpIfRequested(parsed)) {
                return;
            }
        } catch (CommandLine.ParameterException e) {
            commandLine.getErr().println(e.getMessage());
            commandLine.usage(commandLine.getErr());
            System.exit(CommandLine.ExitCode.USAGE);
            return;
        }

        LoadedConfiguration configuration;
        try {
            configuration = new YamlConfigFile().load(cli.configPath());
        } catch (ConfigurationException e) {
            commandLine.getErr().println("Error loading configuration: " + e.getMessage());
            System.exit(CommandLine.ExitCode.SOFTWARE);
            return;
        }

        new SpringApplicationBuilder(TheatrumApplication.class)
                .properties(Map.of("server.port", configuration.server().httpPort()))
                // The config file is parsed before the context exists, so hand it in as a singleton.
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("loadedConfiguration", configuration))
                .run(args);
    }
}
