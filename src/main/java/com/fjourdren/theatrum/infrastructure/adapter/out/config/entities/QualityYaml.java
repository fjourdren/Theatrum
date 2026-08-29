package com.fjourdren.theatrum.infrastructure.adapter.out.config.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class QualityYaml {
    @JsonProperty("width")
    private int width;

    @JsonProperty("height")
    private int height;

    @JsonProperty("framerate")
    private int framerate;

    @JsonProperty("bitrate")
    private String bitrate = "";

    @JsonProperty("codec")
    private String codec = "";

    @JsonProperty("audio")
    private AudioYaml audio = new AudioYaml();
}
