package com.fjourdren.theatrum.infrastructure.adapter.out.config.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** A null format means it is not configured (Go used pointers here). */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DistributionYaml {
    @JsonProperty("hls")
    private HlsYaml hls;

    @JsonProperty("dash")
    private DashYaml dash;
}
