package com.fjourdren.theatrum.application.port.out;

import com.fjourdren.theatrum.application.port.out.exception.ConfigurationException;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;

import java.nio.file.Path;

public interface ConfigurationPort {

    /**
     * Loads and validates the configuration.
     *
     * @throws ConfigurationException when the file cannot be read, parsed or is invalid
     */
    LoadedConfiguration load(Path configPath);
}
