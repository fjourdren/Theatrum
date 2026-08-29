package com.fjourdren.theatrum.domain.model;

import com.fjourdren.theatrum.domain.constant.VideoConstants;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A configured channel. Qualities are kept in a {@link LinkedHashMap} so FFmpeg argument
 * ordering is deterministic (Go map iteration order was random).
 */
public record Stream(
        StreamType type,
        String path,
        Map<String, Quality> qualities,
        Distribution distribution,

        // video_unencoded streams
        String videoInputPath,
        boolean deleteAfterEncoding,

        // live streams
        String liveStreamKey,
        String authTokenTemplate,

        // live and restream streams
        Record record,
        Viewers viewers,
        Views views,
        Thumbnail thumbnail,

        // restream streams
        String sourceUrl) {

    public Stream {
        qualities = qualities == null ? Map.of() : new LinkedHashMap<>(qualities);
        distribution = distribution == null ? Distribution.none() : distribution;
        videoInputPath = videoInputPath == null ? "" : videoInputPath;
        liveStreamKey = liveStreamKey == null ? "" : liveStreamKey;
        authTokenTemplate = authTokenTemplate == null ? "" : authTokenTemplate;
        record = record == null ? Record.disabled() : record;
        viewers = viewers == null ? Viewers.disabled() : viewers;
        views = views == null ? Views.disabled() : views;
        thumbnail = thumbnail == null ? Thumbnail.disabled() : thumbnail;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
        path = path == null ? "" : path;
    }

    public String getMasterPlaylistTemplatePath() {
        return path + "/" + VideoConstants.MASTER_PLAYLIST;
    }

    public String getDashManifestTemplatePath() {
        return path + "/" + VideoConstants.DASH_MANIFEST;
    }

    public boolean multiQuality() {
        return !qualities.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .type(type).path(path).qualities(qualities).distribution(distribution)
                .videoInputPath(videoInputPath).deleteAfterEncoding(deleteAfterEncoding)
                .liveStreamKey(liveStreamKey).authTokenTemplate(authTokenTemplate)
                .record(record).viewers(viewers).views(views).thumbnail(thumbnail)
                .sourceUrl(sourceUrl);
    }

    public static final class Builder {
        private StreamType type;
        private String path = "";
        private Map<String, Quality> qualities = new LinkedHashMap<>();
        private Distribution distribution = Distribution.none();
        private String videoInputPath = "";
        private boolean deleteAfterEncoding;
        private String liveStreamKey = "";
        private String authTokenTemplate = "";
        private Record record = Record.disabled();
        private Viewers viewers = Viewers.disabled();
        private Views views = Views.disabled();
        private Thumbnail thumbnail = Thumbnail.disabled();
        private String sourceUrl = "";

        public Builder type(StreamType type) {
            this.type = type;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder qualities(Map<String, Quality> qualities) {
            this.qualities = qualities == null ? new LinkedHashMap<>() : new LinkedHashMap<>(qualities);
            return this;
        }

        public Builder quality(String name, Quality quality) {
            this.qualities.put(name, quality);
            return this;
        }

        public Builder distribution(Distribution distribution) {
            this.distribution = distribution;
            return this;
        }

        public Builder hls(Hls hls) {
            this.distribution = new Distribution(hls, this.distribution.dash());
            return this;
        }

        public Builder dash(Dash dash) {
            this.distribution = new Distribution(this.distribution.hls(), dash);
            return this;
        }

        public Builder videoInputPath(String videoInputPath) {
            this.videoInputPath = videoInputPath;
            return this;
        }

        public Builder deleteAfterEncoding(boolean deleteAfterEncoding) {
            this.deleteAfterEncoding = deleteAfterEncoding;
            return this;
        }

        public Builder liveStreamKey(String liveStreamKey) {
            this.liveStreamKey = liveStreamKey;
            return this;
        }

        public Builder authTokenTemplate(String authTokenTemplate) {
            this.authTokenTemplate = authTokenTemplate;
            return this;
        }

        public Builder record(Record record) {
            this.record = record;
            return this;
        }

        public Builder viewers(Viewers viewers) {
            this.viewers = viewers;
            return this;
        }

        public Builder views(Views views) {
            this.views = views;
            return this;
        }

        public Builder thumbnail(Thumbnail thumbnail) {
            this.thumbnail = thumbnail;
            return this;
        }

        public Builder sourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
            return this;
        }

        public Stream build() {
            return new Stream(type, path, qualities, distribution, videoInputPath, deleteAfterEncoding,
                    liveStreamKey, authTokenTemplate, record, viewers, views, thumbnail, sourceUrl);
        }
    }
}
