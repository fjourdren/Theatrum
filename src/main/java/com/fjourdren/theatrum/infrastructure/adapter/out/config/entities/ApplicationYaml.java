package com.fjourdren.theatrum.infrastructure.adapter.out.config.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApplicationYaml {
    @JsonProperty("public_path")
    private String publicPath = "";

    @JsonProperty("all_streams_playlist")
    private AllStreamsPlaylistYaml allStreamsPlaylist = new AllStreamsPlaylistYaml();
}
