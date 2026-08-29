package com.fjourdren.theatrum.infrastructure.adapter.out.config.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class StreamYaml {
    @JsonProperty("type")
    private String type = "";

    @JsonProperty("path")
    private String path = "";

    @JsonProperty("qualities")
    private Map<String, QualityYaml> qualities = new LinkedHashMap<>();

    @JsonProperty("distribution")
    private DistributionYaml distribution = new DistributionYaml();

    /** video_unencoded streams only. */
    @JsonProperty("video_input_path")
    private String videoInputPath = "";

    /** Delete the source file after encoding (default: false). */
    @JsonProperty("delete_after_encoding")
    private boolean deleteAfterEncoding;

    /** live streams only. */
    @JsonProperty("live_stream_key")
    private String liveStreamKey = "";

    /** XOR auth template, e.g. "{username}" or "{room_id}{username}". */
    @JsonProperty("auth_token_template")
    private String authTokenTemplate = "";

    @JsonProperty("record")
    private RecordYaml record = new RecordYaml();

    @JsonProperty("viewers")
    private ViewersYaml viewers = new ViewersYaml();

    @JsonProperty("views")
    private ViewsYaml views = new ViewsYaml();

    @JsonProperty("thumbnail")
    private ThumbnailYaml thumbnail = new ThumbnailYaml();

    /** restream streams only. */
    @JsonProperty("source_url")
    private String sourceUrl = "";
}
