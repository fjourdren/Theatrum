package com.fjourdren.theatrum.infrastructure.adapter.out.config.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerYaml {
    @JsonProperty("http")
    private int httpPort;

    @JsonProperty("rtmp")
    private int rtmpPort;

    @JsonProperty("rtmp_config")
    private RtmpYaml rtmp = new RtmpYaml();
}
