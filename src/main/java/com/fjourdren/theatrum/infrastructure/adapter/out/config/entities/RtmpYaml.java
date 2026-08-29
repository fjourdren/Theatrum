package com.fjourdren.theatrum.infrastructure.adapter.out.config.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RtmpYaml {
    /** Seconds to wait before cleaning up a disconnected stream (default: 30). */
    @JsonProperty("reconnect_delay")
    private int reconnectDelay;

    /** Seconds to wait before removing stream files (default: 30). */
    @JsonProperty("cleanup_delay")
    private int cleanupDelay;
}
