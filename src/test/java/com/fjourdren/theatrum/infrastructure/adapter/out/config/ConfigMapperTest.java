package com.fjourdren.theatrum.infrastructure.adapter.out.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fjourdren.theatrum.domain.model.AllStreamsPlaylist;
import com.fjourdren.theatrum.domain.model.Application;
import com.fjourdren.theatrum.domain.model.Audio;
import com.fjourdren.theatrum.domain.model.Dash;
import com.fjourdren.theatrum.domain.model.Distribution;
import com.fjourdren.theatrum.domain.model.Hls;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.domain.model.Quality;
import com.fjourdren.theatrum.domain.model.Record;
import com.fjourdren.theatrum.domain.model.Rtmp;
import com.fjourdren.theatrum.domain.model.Server;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import com.fjourdren.theatrum.domain.model.Thumbnail;
import com.fjourdren.theatrum.domain.model.Viewers;
import com.fjourdren.theatrum.domain.model.Views;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.AllStreamsPlaylistYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ApplicationYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.AudioYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ChannelYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ConfigYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.DashYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.DistributionYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.HlsYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.QualityYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.RecordYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.RtmpYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ServerYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.StreamYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ThumbnailYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ViewersYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ViewsYaml;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigMapperTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    // ---------------------------------------------------------------- helpers

    private static ServerYaml server(int http, int rtmp, RtmpYaml rtmpConfig) {
        ServerYaml server = new ServerYaml();
        server.setHttpPort(http);
        server.setRtmpPort(rtmp);
        server.setRtmp(rtmpConfig);
        return server;
    }

    private static RtmpYaml rtmp(int reconnectDelay, int cleanupDelay) {
        RtmpYaml rtmp = new RtmpYaml();
        rtmp.setReconnectDelay(reconnectDelay);
        rtmp.setCleanupDelay(cleanupDelay);
        return rtmp;
    }

    private static QualityYaml quality(int width, int height, int framerate, String bitrate, String codec,
                                       String audioBitrate, String audioCodec) {
        AudioYaml audio = new AudioYaml();
        audio.setBitrate(audioBitrate);
        audio.setCodec(audioCodec);

        QualityYaml quality = new QualityYaml();
        quality.setWidth(width);
        quality.setHeight(height);
        quality.setFramerate(framerate);
        quality.setBitrate(bitrate);
        quality.setCodec(codec);
        quality.setAudio(audio);
        return quality;
    }

    private static HlsYaml hls(int segmentDuration, int windowSize) {
        HlsYaml hls = new HlsYaml();
        hls.setSegmentDuration(segmentDuration);
        hls.setWindowSize(windowSize);
        return hls;
    }

    private static DashYaml dash(int segmentDuration, int windowSize) {
        DashYaml dash = new DashYaml();
        dash.setSegmentDuration(segmentDuration);
        dash.setWindowSize(windowSize);
        return dash;
    }

    private static DistributionYaml distribution(HlsYaml hls, DashYaml dash) {
        DistributionYaml distribution = new DistributionYaml();
        distribution.setHls(hls);
        distribution.setDash(dash);
        return distribution;
    }

    private static StreamYaml stream(String type, String path, DistributionYaml distribution) {
        StreamYaml stream = new StreamYaml();
        stream.setType(type);
        stream.setPath(path);
        stream.setDistribution(distribution);
        return stream;
    }

    private static ChannelYaml channel(StreamYaml stream) {
        ChannelYaml channel = new ChannelYaml();
        channel.setStream(stream);
        return channel;
    }

    private static RecordYaml record(boolean enabled, String path) {
        RecordYaml record = new RecordYaml();
        record.setEnabled(enabled);
        record.setPath(path);
        return record;
    }

    // ------------------------------------------------------------------ tests

    /** The one entry point that has to be total: {@code load()} feeds it whatever parsed. */
    @Nested
    class ToDomainConfiguration {

        @Test
        void allTopLevelBlocksMapped() throws Exception {
            ConfigYaml config = YAML.readValue("""
                    application:
                      public_path: "http://localhost:8080"
                    server:
                      http: 8080
                      rtmp: 1935
                    channels:
                      "/vod/{name}":
                        stream:
                          type: video_encoded
                          path: "videos/{name}"
                          distribution:
                            hls:
                              segment_duration: 6
                    """, ConfigYaml.class);

            LoadedConfiguration result = ConfigMapper.INSTANCE.toDomainConfiguration(config);

            assertThat(result.application().publicPath()).isEqualTo("http://localhost:8080");
            assertThat(result.server().httpPort()).isEqualTo(8080);
            assertThat(result.channels().keySet()).containsExactly("/vod/{name}");
        }
    }

    @Nested
    class ToDomainServer {

        @Test
        void allFieldsMapped() {
            Server result = ConfigMapper.INSTANCE.toDomainServer(server(8080, 1935, rtmp(60, 45)));

            assertThat(result.httpPort()).isEqualTo(8080);
            assertThat(result.rtmpPort()).isEqualTo(1935);
            assertThat(result.rtmp().reconnectDelay()).isEqualTo(60);
            assertThat(result.rtmp().cleanupDelay()).isEqualTo(45);
        }

        @Test
        void rtmpDefaultsWhenZero() {
            Server result = ConfigMapper.INSTANCE.toDomainServer(server(8080, 1935, new RtmpYaml()));

            assertThat(result.rtmp().reconnectDelay()).isEqualTo(30);
            assertThat(result.rtmp().cleanupDelay()).isEqualTo(30);
        }

        @Test
        void negativeValuesDefaultTo30() {
            Server result = ConfigMapper.INSTANCE.toDomainServer(server(8080, 1935, rtmp(-1, -5)));

            assertThat(result.rtmp().reconnectDelay()).isEqualTo(30);
            assertThat(result.rtmp().cleanupDelay()).isEqualTo(30);
        }
    }

    @Nested
    class ToDomainQuality {

        @Test
        void allFieldsMapped() {
            Quality result = ConfigMapper.INSTANCE.toDomainQuality(
                    quality(1920, 1080, 30, "5000k", "libx264", "128k", "aac"));

            assertThat(result.width()).isEqualTo(1920);
            assertThat(result.height()).isEqualTo(1080);
            assertThat(result.framerate()).isEqualTo(30);
            assertThat(result.bitrate()).isEqualTo("5000k");
            assertThat(result.codec()).isEqualTo("libx264");
            assertThat(result.audio().bitrate()).isEqualTo("128k");
            assertThat(result.audio().codec()).isEqualTo("aac");
        }
    }

    @Nested
    class ToDomainStream {

        @Test
        void allFieldsMapped() {
            StreamYaml yaml = stream("live", "live/{username}", distribution(hls(4, 5), null));
            yaml.setQualities(new LinkedHashMap<>(Map.of(
                    "low", quality(640, 360, 30, "800k", "libx264", "64k", "aac"))));
            yaml.setLiveStreamKey("secret");
            yaml.setAuthTokenTemplate("{username}");

            RecordYaml record = new RecordYaml();
            record.setEnabled(true);
            record.setPath("recordings/{username}");
            yaml.setRecord(record);

            ViewersYaml viewers = new ViewersYaml();
            viewers.setEnabled(true);
            viewers.setWindow(30);
            yaml.setViewers(viewers);

            ViewsYaml views = new ViewsYaml();
            views.setEnabled(true);
            views.setWindow(15);
            yaml.setViews(views);

            ThumbnailYaml thumbnail = new ThumbnailYaml();
            thumbnail.setEnabled(true);
            thumbnail.setInterval(5);
            yaml.setThumbnail(thumbnail);

            Stream result = ConfigMapper.INSTANCE.toDomainStream(yaml);

            assertThat(result.type()).isEqualTo(StreamType.LIVE);
            assertThat(result.path()).isEqualTo("live/{username}");
            assertThat(result.qualities()).hasSize(1);
            assertThat(result.distribution().hls().segmentDuration()).isEqualTo(4);
            assertThat(result.distribution().hls().windowSize()).isEqualTo(5);
            assertThat(result.liveStreamKey()).isEqualTo("secret");
            assertThat(result.authTokenTemplate()).isEqualTo("{username}");
            assertThat(result.record().enabled()).isTrue();
            assertThat(result.record().path()).isEqualTo("recordings/{username}");
            assertThat(result.viewers().enabled()).isTrue();
            assertThat(result.viewers().window()).isEqualTo(30);
            assertThat(result.views().enabled()).isTrue();
            assertThat(result.views().window()).isEqualTo(15);
            assertThat(result.thumbnail().enabled()).isTrue();
            assertThat(result.thumbnail().interval()).isEqualTo(5);
        }

        @Test
        void windowSizeDefaultWhenZero() {
            Stream result = ConfigMapper.INSTANCE.toDomainStream(
                    stream("video_encoded", "videos/{name}", distribution(hls(6, 0), null)));

            assertThat(result.distribution().hls().windowSize()).isEqualTo(3);
        }

        @Test
        void dashOnlyDistributionMapped() {
            StreamYaml yaml = stream("live", "live/{username}", distribution(null, dash(4, 5)));
            yaml.setLiveStreamKey("secret");
            yaml.setAuthTokenTemplate("{username}");

            Stream result = ConfigMapper.INSTANCE.toDomainStream(yaml);

            assertThat(result.distribution().hls()).isNull();
            assertThat(result.distribution().dash()).isNotNull();
            assertThat(result.distribution().dash().segmentDuration()).isEqualTo(4);
            assertThat(result.distribution().dash().windowSize()).isEqualTo(5);
        }

        @Test
        void dashWindowSizeDefaultWhenZero() {
            Stream result = ConfigMapper.INSTANCE.toDomainStream(
                    stream("live", "live/{username}", distribution(null, dash(4, 0))));

            assertThat(result.distribution().dash().windowSize()).isEqualTo(3);
        }

        @Test
        void dualModeDistributionMapped() {
            Stream result = ConfigMapper.INSTANCE.toDomainStream(
                    stream("live", "live/{username}", distribution(hls(2, 3), dash(2, 3))));

            assertThat(result.distribution().hls()).isNotNull();
            assertThat(result.distribution().dash()).isNotNull();
            assertThat(result.distribution().hls().segmentDuration()).isEqualTo(2);
            assertThat(result.distribution().dash().segmentDuration()).isEqualTo(2);
            assertThat(result.distribution().isDualMode()).isTrue();
        }

        @Test
        void sourceUrlMapped() {
            StreamYaml yaml = stream("restream", "restream/mystream", distribution(hls(2, 3), null));
            yaml.setSourceUrl("rtmp://external-server/live/stream_key");

            Stream result = ConfigMapper.INSTANCE.toDomainStream(yaml);

            assertThat(result.type()).isEqualTo(StreamType.RESTREAM);
            assertThat(result.sourceUrl()).isEqualTo("rtmp://external-server/live/stream_key");
        }

        @Test
        void videoUnencodedFieldsMapped() {
            StreamYaml yaml = stream("video_unencoded", "videos/{name}", distribution(hls(6, 0), null));
            yaml.setVideoInputPath("input/{name}.mp4");
            yaml.setDeleteAfterEncoding(true);

            Stream result = ConfigMapper.INSTANCE.toDomainStream(yaml);

            assertThat(result.type()).isEqualTo(StreamType.VIDEO_UNENCODED);
            assertThat(result.videoInputPath()).isEqualTo("input/{name}.mp4");
            assertThat(result.deleteAfterEncoding()).isTrue();
        }

        @Test
        void absentOptionalBlocksMapToDisabled() {
            StreamYaml yaml = stream("live", "live/{username}", distribution(hls(2, 3), null));
            yaml.setRecord(null);
            yaml.setViewers(null);
            yaml.setViews(null);
            yaml.setThumbnail(null);
            yaml.setQualities(null);

            Stream result = ConfigMapper.INSTANCE.toDomainStream(yaml);

            assertThat(result.record().enabled()).isFalse();
            assertThat(result.record().path()).isEmpty();
            assertThat(result.viewers().enabled()).isFalse();
            assertThat(result.viewers().window()).isZero();
            assertThat(result.views().enabled()).isFalse();
            assertThat(result.views().window()).isZero();
            assertThat(result.thumbnail().enabled()).isFalse();
            assertThat(result.thumbnail().interval()).isZero();
            assertThat(result.qualities()).isEmpty();
        }

        @Test
        void absentDistributionBlockMapsToNone() {
            StreamYaml yaml = stream("live", "live/{username}", null);

            Stream result = ConfigMapper.INSTANCE.toDomainStream(yaml);

            assertThat(result.distribution().hlsEnabled()).isFalse();
            assertThat(result.distribution().dashEnabled()).isFalse();
        }

        @Test
        void unknownTypeMapsToNull() {
            Stream result = ConfigMapper.INSTANCE.toDomainStream(
                    stream("bogus", "videos/x", distribution(hls(2, 3), null)));

            assertThat(result.type()).isNull();
        }

        @Test
        void qualitiesKeepConfigOrder() {
            StreamYaml yaml = stream("live", "live/{username}", distribution(hls(2, 3), null));
            Map<String, QualityYaml> qualities = new LinkedHashMap<>();
            qualities.put("low", quality(640, 360, 30, "800k", "libx264", "64k", "aac"));
            qualities.put("medium", quality(1280, 720, 30, "2500k", "libx264", "96k", "aac"));
            qualities.put("high", quality(1920, 1080, 60, "6000k", "libx264", "128k", "aac"));
            yaml.setQualities(qualities);

            Stream result = ConfigMapper.INSTANCE.toDomainStream(yaml);

            assertThat(result.qualities()).containsExactly(
                    org.assertj.core.api.Assertions.entry("low", result.qualities().get("low")),
                    org.assertj.core.api.Assertions.entry("medium", result.qualities().get("medium")),
                    org.assertj.core.api.Assertions.entry("high", result.qualities().get("high")));
            assertThat(result.qualities().get("high").width()).isEqualTo(1920);
        }
    }

    @Nested
    class ToDomainChannels {

        @Test
        void bothChannelsMapped() {
            Map<String, ChannelYaml> channels = new LinkedHashMap<>();
            channels.put("/user/{username}",
                    channel(stream("live", "live/{username}", distribution(hls(2, 3), null))));
            channels.put("/vod/{name}",
                    channel(stream("video_encoded", "videos/{name}", distribution(hls(6, 0), null))));

            Map<String, Stream> result = ConfigMapper.INSTANCE.toDomainChannels(channels);

            assertThat(result).hasSize(2);
            assertThat(result.get("/user/{username}").type()).isEqualTo(StreamType.LIVE);
            assertThat(result.get("/vod/{name}").type()).isEqualTo(StreamType.VIDEO_ENCODED);
        }

        @Test
        void channelOrderPreserved() {
            Map<String, ChannelYaml> channels = new LinkedHashMap<>();
            channels.put("/c", channel(stream("live", "live/c", distribution(hls(2, 3), null))));
            channels.put("/a", channel(stream("live", "live/a", distribution(hls(2, 3), null))));
            channels.put("/b", channel(stream("live", "live/b", distribution(hls(2, 3), null))));

            assertThat(ConfigMapper.INSTANCE.toDomainChannels(channels).keySet())
                    .containsExactly("/c", "/a", "/b");
        }

        @Test
        void emptyChannels() {
            assertThat(ConfigMapper.INSTANCE.toDomainChannels(new LinkedHashMap<>())).isEmpty();
        }
    }

    @Nested
    class ToDomainApplication {

        @Test
        void publicPathMapped() {
            ApplicationYaml app = new ApplicationYaml();
            app.setPublicPath("http://localhost:8080");

            assertThat(ConfigMapper.INSTANCE.toDomainApplication(app).publicPath()).isEqualTo("http://localhost:8080");
        }

        @Test
        void allStreamsPlaylistEnabled() {
            AllStreamsPlaylistYaml playlist = new AllStreamsPlaylistYaml();
            playlist.setEnabled(true);
            playlist.setPath("/all.m3u8");
            ApplicationYaml app = new ApplicationYaml();
            app.setAllStreamsPlaylist(playlist);

            Application result = ConfigMapper.INSTANCE.toDomainApplication(app);

            assertThat(result.allStreamsPlaylist().enabled()).isTrue();
            assertThat(result.allStreamsPlaylist().path()).isEqualTo("/all.m3u8");
        }

        @Test
        void allStreamsPlaylistDisabled() {
            ApplicationYaml app = new ApplicationYaml();
            app.setAllStreamsPlaylist(new AllStreamsPlaylistYaml());

            assertThat(ConfigMapper.INSTANCE.toDomainApplication(app).allStreamsPlaylist().enabled()).isFalse();
        }
    }

    /** Proves the {@code @JsonProperty} names match the YAML keys the Go tags declared. */
    @Nested
    class YamlBinding {

        @Test
        void fullConfigParsesAndMaps() throws Exception {
            String yaml = """
                    application:
                      public_path: "http://localhost:8080"
                      all_streams_playlist:
                        enabled: true
                        path: "all.m3u8"
                    server:
                      http: 8080
                      rtmp: 1935
                      rtmp_config:
                        reconnect_delay: 60
                        cleanup_delay: 45
                    stream_templates:
                      base:
                        stream:
                          type: video_unencoded
                          path: "videos/{name}"
                          video_input_path: "input/{name}.mp4"
                          delete_after_encoding: true
                          distribution:
                            hls:
                              segment_duration: 6
                    channels:
                      "/user/{username}":
                        stream:
                          type: live
                          path: "live/{username}"
                          live_stream_key: "secret"
                          auth_token_template: "{username}"
                          qualities:
                            low:
                              width: 640
                              height: 360
                              framerate: 30
                              bitrate: "800k"
                              codec: "libx264"
                              audio:
                                bitrate: "64k"
                                codec: "aac"
                            high:
                              width: 1920
                              height: 1080
                              framerate: 60
                              bitrate: "6000k"
                              codec: "libx264"
                              audio:
                                bitrate: "128k"
                                codec: "aac"
                          distribution:
                            hls:
                              segment_duration: 2
                              window_size: 5
                            dash:
                              segment_duration: 2
                              window_size: 5
                          record:
                            enabled: true
                            path: "recordings/{username}"
                          viewers:
                            enabled: true
                            window: 30
                          views:
                            enabled: true
                            window: 15
                          thumbnail:
                            enabled: true
                            interval: 5
                      "/restream/x":
                        stream:
                          type: restream
                          path: "restream/x"
                          source_url: "rtmp://external-server/live/key"
                          distribution:
                            hls:
                              segment_duration: 4
                    unknown_future_key: ignored
                    """;

            ConfigYaml config = YAML.readValue(yaml, ConfigYaml.class);

            Application application = ConfigMapper.INSTANCE.toDomainApplication(config.getApplication());
            assertThat(application.publicPath()).isEqualTo("http://localhost:8080");
            assertThat(application.allStreamsPlaylist().enabled()).isTrue();
            assertThat(application.allStreamsPlaylist().path()).isEqualTo("all.m3u8");

            Server server = ConfigMapper.INSTANCE.toDomainServer(config.getServer());
            assertThat(server.httpPort()).isEqualTo(8080);
            assertThat(server.rtmpPort()).isEqualTo(1935);
            assertThat(server.rtmp().reconnectDelay()).isEqualTo(60);
            assertThat(server.rtmp().cleanupDelay()).isEqualTo(45);

            StreamYaml template = config.getStreamTemplates().get("base").getStream();
            assertThat(template.getType()).isEqualTo("video_unencoded");
            assertThat(template.getVideoInputPath()).isEqualTo("input/{name}.mp4");
            assertThat(template.isDeleteAfterEncoding()).isTrue();

            Map<String, Stream> channels = ConfigMapper.INSTANCE.toDomainChannels(config.getChannels());
            assertThat(channels.keySet()).containsExactly("/user/{username}", "/restream/x");

            Stream live = channels.get("/user/{username}");
            assertThat(live.type()).isEqualTo(StreamType.LIVE);
            assertThat(live.path()).isEqualTo("live/{username}");
            assertThat(live.liveStreamKey()).isEqualTo("secret");
            assertThat(live.authTokenTemplate()).isEqualTo("{username}");
            assertThat(live.qualities().keySet()).containsExactly("low", "high");
            assertThat(live.qualities().get("low").audio().codec()).isEqualTo("aac");
            assertThat(live.qualities().get("high").bitrate()).isEqualTo("6000k");
            assertThat(live.distribution().isDualMode()).isTrue();
            assertThat(live.distribution().hls()).isEqualTo(new com.fjourdren.theatrum.domain.model.Hls(2, 5));
            assertThat(live.distribution().dash()).isEqualTo(new com.fjourdren.theatrum.domain.model.Dash(2, 5));
            assertThat(live.record().enabled()).isTrue();
            assertThat(live.record().path()).isEqualTo("recordings/{username}");
            assertThat(live.viewers().window()).isEqualTo(30);
            assertThat(live.views().window()).isEqualTo(15);
            assertThat(live.thumbnail().interval()).isEqualTo(5);

            Stream restream = channels.get("/restream/x");
            assertThat(restream.type()).isEqualTo(StreamType.RESTREAM);
            assertThat(restream.sourceUrl()).isEqualTo("rtmp://external-server/live/key");
            assertThat(restream.distribution().hls().windowSize()).isEqualTo(3);
            assertThat(restream.distribution().dash()).isNull();
        }

        @Test
        void minimalConfigUsesZeroValues() throws Exception {
            ConfigYaml config = YAML.readValue("""
                    server:
                      http: 8080
                      rtmp: 1935
                    channels:
                      "/vod/{name}":
                        stream:
                          type: video_encoded
                          path: "videos/{name}"
                          distribution:
                            dash:
                              segment_duration: 6
                    """, ConfigYaml.class);

            assertThat(ConfigMapper.INSTANCE.toDomainApplication(config.getApplication()).publicPath()).isEmpty();
            assertThat(ConfigMapper.INSTANCE.toDomainServer(config.getServer()).rtmp().cleanupDelay()).isEqualTo(30);
            assertThat(config.getStreamTemplates()).isEmpty();

            Stream vod = ConfigMapper.INSTANCE.toDomainChannels(config.getChannels()).get("/vod/{name}");
            assertThat(vod.type()).isEqualTo(StreamType.VIDEO_ENCODED);
            assertThat(vod.qualities()).isEmpty();
            assertThat(vod.distribution().hls()).isNull();
            assertThat(vod.distribution().dash().windowSize()).isEqualTo(3);
            assertThat(vod.record().enabled()).isFalse();
            assertThat(vod.viewers().enabled()).isFalse();
            assertThat(vod.views().enabled()).isFalse();
            assertThat(vod.thumbnail().enabled()).isFalse();
            assertThat(vod.sourceUrl()).isEmpty();
            assertThat(vod.videoInputPath()).isEmpty();
            assertThat(vod.deleteAfterEncoding()).isFalse();
        }
    }

    // --------------------------------------------- the nested block mappings, on their own

    @Nested
    class ToDomainRtmp {

        @Test
        void configuredDelaysKept() {
            Rtmp result = ConfigMapper.INSTANCE.toDomainRtmp(rtmp(60, 45));

            assertThat(result).isEqualTo(new Rtmp(60, 45));
        }

        @Test
        void nonPositiveDelaysFallBackTo30() {
            assertThat(ConfigMapper.INSTANCE.toDomainRtmp(rtmp(0, -1))).isEqualTo(new Rtmp(30, 30));
        }
    }

    @Nested
    class ToDomainAllStreamsPlaylist {

        @Test
        void allFieldsMapped() {
            AllStreamsPlaylistYaml yaml = new AllStreamsPlaylistYaml();
            yaml.setEnabled(true);
            yaml.setPath("all.m3u8");

            assertThat(ConfigMapper.INSTANCE.toDomainAllStreamsPlaylist(yaml))
                    .isEqualTo(new AllStreamsPlaylist(true, "all.m3u8"));
        }

        @Test
        void defaultsToDisabledWithEmptyPath() {
            assertThat(ConfigMapper.INSTANCE.toDomainAllStreamsPlaylist(new AllStreamsPlaylistYaml()))
                    .isEqualTo(new AllStreamsPlaylist(false, ""));
        }
    }

    @Nested
    class ToDomainAudio {

        @Test
        void allFieldsMapped() {
            AudioYaml yaml = new AudioYaml();
            yaml.setBitrate("128k");
            yaml.setCodec("aac");

            assertThat(ConfigMapper.INSTANCE.toDomainAudio(yaml)).isEqualTo(new Audio("128k", "aac"));
        }
    }

    @Nested
    class ToDomainHlsAndDash {

        @Test
        void allFieldsMapped() {
            assertThat(ConfigMapper.INSTANCE.toDomainHls(hls(4, 5))).isEqualTo(new Hls(4, 5));
            assertThat(ConfigMapper.INSTANCE.toDomainDash(dash(4, 5))).isEqualTo(new Dash(4, 5));
        }

        @Test
        void nonPositiveWindowSizeFallsBackTo3() {
            assertThat(ConfigMapper.INSTANCE.toDomainHls(hls(6, 0))).isEqualTo(new Hls(6, 3));
            assertThat(ConfigMapper.INSTANCE.toDomainDash(dash(6, -1))).isEqualTo(new Dash(6, 3));
        }
    }

    /** A missing format must stay {@code null} — that is what "this format is off" means. */
    @Nested
    class ToDomainDistribution {

        @Test
        void bothFormatsMapped() {
            Distribution result = ConfigMapper.INSTANCE.toDomainDistribution(distribution(hls(2, 3), dash(2, 3)));

            assertThat(result.isDualMode()).isTrue();
            assertThat(result.hls()).isEqualTo(new Hls(2, 3));
            assertThat(result.dash()).isEqualTo(new Dash(2, 3));
        }

        @Test
        void absentFormatStaysNull() {
            assertThat(ConfigMapper.INSTANCE.toDomainDistribution(distribution(hls(2, 3), null)).dash()).isNull();
            assertThat(ConfigMapper.INSTANCE.toDomainDistribution(distribution(null, dash(2, 3))).hls()).isNull();
        }

        @Test
        void neitherFormatConfigured() {
            assertThat(ConfigMapper.INSTANCE.toDomainDistribution(new DistributionYaml()))
                    .isEqualTo(Distribution.none());
        }
    }

    @Nested
    class ToDomainRecord {

        @Test
        void allFieldsMapped() {
            assertThat(ConfigMapper.INSTANCE.toDomainRecord(record(true, "recordings/{username}")))
                    .isEqualTo(new Record(true, "recordings/{username}"));
        }

        @Test
        void emptyPathMeansInPlaceRecording() {
            assertThat(ConfigMapper.INSTANCE.toDomainRecord(record(true, ""))).isEqualTo(new Record(true, ""));
        }
    }

    @Nested
    class ToDomainViewersViewsAndThumbnail {

        @Test
        void allFieldsMapped() {
            ViewersYaml viewers = new ViewersYaml();
            viewers.setEnabled(true);
            viewers.setWindow(30);

            ViewsYaml views = new ViewsYaml();
            views.setEnabled(true);
            views.setWindow(15);

            ThumbnailYaml thumbnail = new ThumbnailYaml();
            thumbnail.setEnabled(true);
            thumbnail.setInterval(5);

            assertThat(ConfigMapper.INSTANCE.toDomainViewers(viewers)).isEqualTo(new Viewers(true, 30));
            assertThat(ConfigMapper.INSTANCE.toDomainViews(views)).isEqualTo(new Views(true, 15));
            assertThat(ConfigMapper.INSTANCE.toDomainThumbnail(thumbnail)).isEqualTo(new Thumbnail(true, 5));
        }

        @Test
        void zeroValuedBlocksMapToDisabled() {
            assertThat(ConfigMapper.INSTANCE.toDomainViewers(new ViewersYaml())).isEqualTo(Viewers.disabled());
            assertThat(ConfigMapper.INSTANCE.toDomainViews(new ViewsYaml())).isEqualTo(Views.disabled());
            assertThat(ConfigMapper.INSTANCE.toDomainThumbnail(new ThumbnailYaml())).isEqualTo(Thumbnail.disabled());
        }
    }

    @Nested
    class ToDomainStreamType {

        @Test
        void everyConfiguredValueResolves() {
            assertThat(ConfigMapper.INSTANCE.toDomainStreamType("video_unencoded"))
                    .isEqualTo(StreamType.VIDEO_UNENCODED);
            assertThat(ConfigMapper.INSTANCE.toDomainStreamType("video_encoded"))
                    .isEqualTo(StreamType.VIDEO_ENCODED);
            assertThat(ConfigMapper.INSTANCE.toDomainStreamType("live")).isEqualTo(StreamType.LIVE);
            assertThat(ConfigMapper.INSTANCE.toDomainStreamType("restream")).isEqualTo(StreamType.RESTREAM);
        }

        /** Unknown and absent both mean null — MapStruct's enum default would have thrown instead. */
        @Test
        void unknownAndNullMapToNull() {
            assertThat(ConfigMapper.INSTANCE.toDomainStreamType("bogus")).isNull();
            assertThat(ConfigMapper.INSTANCE.toDomainStreamType(null)).isNull();
        }
    }

    @Nested
    class ToDomainStreamFromChannel {

        @Test
        void unwrapsTheStream() {
            Stream result = ConfigMapper.INSTANCE.toDomainStream(
                    channel(stream("live", "live/{username}", distribution(hls(2, 3), null))));

            assertThat(result.type()).isEqualTo(StreamType.LIVE);
            assertThat(result.path()).isEqualTo("live/{username}");
        }

        @Test
        void emptyChannelMapsToAZeroValuedStream() {
            for (ChannelYaml empty : new ChannelYaml[]{null, channel(null)}) {
                Stream result = ConfigMapper.INSTANCE.toDomainStream(empty);

                assertThat(result.type()).isNull();
                assertThat(result.path()).isEmpty();
                assertThat(result.qualities()).isEmpty();
                assertThat(result.distribution()).isEqualTo(Distribution.none());
                assertThat(result.record()).isEqualTo(Record.disabled());
                assertThat(result.viewers()).isEqualTo(Viewers.disabled());
                assertThat(result.views()).isEqualTo(Views.disabled());
                assertThat(result.thumbnail()).isEqualTo(Thumbnail.disabled());
            }
        }
    }
}
