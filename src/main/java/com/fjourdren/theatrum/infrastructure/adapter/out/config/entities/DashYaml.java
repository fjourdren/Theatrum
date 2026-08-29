package com.fjourdren.theatrum.infrastructure.adapter.out.config.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashYaml {
    @JsonProperty("segment_duration")
    private int segmentDuration;

    /** Segments in the live manifest (0 means default of 3). */
    @JsonProperty("window_size")
    private int windowSize;
}
