package com.fjourdren.theatrum.infrastructure.adapter.out.config.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/** Root of config.yml. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigYaml {
    @JsonProperty("application")
    private ApplicationYaml application = new ApplicationYaml();

    @JsonProperty("server")
    private ServerYaml server = new ServerYaml();

    @JsonProperty("stream_templates")
    private Map<String, StreamTemplateYaml> streamTemplates = new LinkedHashMap<>();

    @JsonProperty("channels")
    private Map<String, ChannelYaml> channels = new LinkedHashMap<>();
}
