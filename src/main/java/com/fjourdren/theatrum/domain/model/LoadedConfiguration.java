package com.fjourdren.theatrum.domain.model;

import java.util.Map;

/** Everything a configuration source yields: application settings, server settings and channels. */
public record LoadedConfiguration(Application application, Server server, Map<String, Stream> channels) {
}
