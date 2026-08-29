package com.fjourdren.theatrum.infrastructure.adapter.out.config;

import com.fjourdren.theatrum.application.port.out.exception.ConfigurationException;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.model.StreamType;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.AudioYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ChannelYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ConfigYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.DashYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.DistributionYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.HlsYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.QualityYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.RecordYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ServerYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.StreamTemplateYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.StreamYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ThumbnailYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ViewersYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ViewsYaml;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlConfigFileTest {

    private final YamlConfigFile validator = new YamlConfigFile();

    // ---------------------------------------------------------------- helpers

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

    private static Map<String, QualityYaml> lowQuality() {
        Map<String, QualityYaml> qualities = new LinkedHashMap<>();
        qualities.put("low", quality(640, 360, 30, "800k", "libx264", "64k", "aac"));
        return qualities;
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

    private static RecordYaml record(boolean enabled, String path) {
        RecordYaml record = new RecordYaml();
        record.setEnabled(enabled);
        record.setPath(path);
        return record;
    }

    private static ViewersYaml viewers(boolean enabled, int window) {
        ViewersYaml viewers = new ViewersYaml();
        viewers.setEnabled(enabled);
        viewers.setWindow(window);
        return viewers;
    }

    private static ViewsYaml views(boolean enabled, int window) {
        ViewsYaml views = new ViewsYaml();
        views.setEnabled(enabled);
        views.setWindow(window);
        return views;
    }

    private static ThumbnailYaml thumbnail(boolean enabled, int interval) {
        ThumbnailYaml thumbnail = new ThumbnailYaml();
        thumbnail.setEnabled(enabled);
        thumbnail.setInterval(interval);
        return thumbnail;
    }

    /** A valid video_encoded stream. */
    private static StreamYaml validStream() {
        StreamYaml stream = new StreamYaml();
        stream.setType(StreamType.VIDEO_ENCODED.value());
        stream.setPath("videos/{name}");
        stream.setQualities(lowQuality());
        stream.setDistribution(distribution(hls(6, 3), null));
        return stream;
    }

    private static StreamYaml validLiveStream() {
        StreamYaml stream = new StreamYaml();
        stream.setType(StreamType.LIVE.value());
        stream.setPath("live/{username}");
        stream.setQualities(lowQuality());
        stream.setDistribution(distribution(hls(2, 3), null));
        stream.setLiveStreamKey("secret");
        stream.setAuthTokenTemplate("{username}");
        return stream;
    }

    private static StreamYaml validRestreamStream() {
        StreamYaml stream = new StreamYaml();
        stream.setType(StreamType.RESTREAM.value());
        stream.setPath("restream/mystream");
        stream.setSourceUrl("rtmp://external-server/live/stream_key");
        stream.setDistribution(distribution(hls(2, 3), null));
        return stream;
    }

    private static ServerYaml server(int http, int rtmp) {
        ServerYaml server = new ServerYaml();
        server.setHttpPort(http);
        server.setRtmpPort(rtmp);
        return server;
    }

    private static ConfigYaml config(ServerYaml server, Map<String, ChannelYaml> channels) {
        ConfigYaml config = new ConfigYaml();
        config.setServer(server);
        config.setChannels(channels);
        return config;
    }

    private static Map<String, ChannelYaml> channels(String name, StreamYaml stream) {
        ChannelYaml channel = new ChannelYaml();
        channel.setStream(stream);
        Map<String, ChannelYaml> channels = new LinkedHashMap<>();
        channels.put(name, channel);
        return channels;
    }

    private static Path writeConfig(Path dir, String content) throws IOException {
        Path configPath = dir.resolve("config.yml");
        Files.writeString(configPath, content);
        return configPath;
    }

    // -------------------------------------------------------------------- parse

    @Nested
    class Parse {

        /** Every block a user may write with nothing under it. */
        private static final String EMPTY_BLOCKS = """
                application:
                  public_path:
                  all_streams_playlist:
                server:
                  rtmp_config:
                channels:
                stream_templates:
                  live_base:
                    stream:
                      type:
                      path:
                      record:
                      viewers:
                      views:
                      thumbnail:
                      distribution:
                      qualities:
                        low:
                          audio:
                """;

        /**
         * A key written with nothing under it hands Jackson an explicit null. Skipping those keeps
         * the entities' field initializers, so everything below the root is non-null once parsed —
         * the invariant {@link ConfigMapper} maps against instead of carrying its own defaults.
         */
        @Test
        void keysWrittenWithNothingUnderThemKeepTheirDefaults() {
            ConfigYaml config = validator.parse(EMPTY_BLOCKS);

            assertThat(config.getApplication().getPublicPath()).isEmpty();
            assertThat(config.getApplication().getAllStreamsPlaylist()).isNotNull();
            assertThat(config.getServer().getRtmp()).isNotNull();
            assertThat(config.getChannels()).isEmpty();

            StreamYaml stream = config.getStreamTemplates().get("live_base").getStream();
            assertThat(stream.getType()).isEmpty();
            assertThat(stream.getPath()).isEmpty();
            assertThat(stream.getRecord()).isNotNull();
            assertThat(stream.getViewers()).isNotNull();
            assertThat(stream.getViews()).isNotNull();
            assertThat(stream.getThumbnail()).isNotNull();
            assertThat(stream.getDistribution()).isNotNull();
            assertThat(stream.getQualities().get("low").getAudio()).isNotNull();
        }

        /** The zero values Go's structs produced, now carried by the entities rather than the mapper. */
        @Test
        void emptyBlocksMapToDomainZeroValues() {
            LoadedConfiguration result = ConfigMapper.INSTANCE.toDomainConfiguration(validator.parse(EMPTY_BLOCKS));

            assertThat(result.application().publicPath()).isEmpty();
            assertThat(result.application().allStreamsPlaylist().enabled()).isFalse();
            assertThat(result.application().allStreamsPlaylist().path()).isEmpty();
            assertThat(result.server().httpPort()).isZero();
            assertThat(result.server().rtmpPort()).isZero();
            assertThat(result.server().rtmp().reconnectDelay()).isEqualTo(30);
            assertThat(result.server().rtmp().cleanupDelay()).isEqualTo(30);
            assertThat(result.channels()).isEmpty();
        }
    }

    // ------------------------------------------------------------ validateConfig

    @Nested
    class ValidateConfig {

        @Test
        void acceptsValidConfig() {
            ConfigYaml config = config(server(8080, 1935), channels("/vod/{name}", validStream()));

            assertThatCode(() -> validator.validateConfig(config)).doesNotThrowAnyException();
        }

        @Test
        void rejectsInvalidHttpPort() {
            ConfigYaml config = config(server(0, 1935), new LinkedHashMap<>());

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("invalid HTTP port: must be greater than 0");
        }

        @Test
        void rejectsInvalidRtmpPort() {
            ConfigYaml config = config(server(8080, -1), new LinkedHashMap<>());

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("invalid RTMP port: must be greater than 0");
        }

        @Test
        void rejectsRootChannelName() {
            ConfigYaml config = config(server(8080, 1935), channels("/", validStream()));

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("invalid channel name: must not be '/'");
        }

        @Test
        void rejectsEmptyChannelName() {
            ConfigYaml config = config(server(8080, 1935), channels("", validStream()));

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("invalid channel name: must not be '/'");
        }

        @Test
        void rejectsRootTemplateName() {
            ConfigYaml config = config(server(8080, 1935), new LinkedHashMap<>());
            StreamTemplateYaml template = new StreamTemplateYaml();
            template.setStream(validStream());
            config.setStreamTemplates(new LinkedHashMap<>(Map.of("/", template)));

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("invalid template name: must not be '/'");
        }

        @Test
        void reportsTemplateContextInErrors() {
            ConfigYaml config = config(server(8080, 1935), new LinkedHashMap<>());
            StreamYaml stream = validStream();
            stream.setType("");
            StreamTemplateYaml template = new StreamTemplateYaml();
            template.setStream(stream);
            config.setStreamTemplates(new LinkedHashMap<>(Map.of("broken", template)));

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .hasMessage("template 'broken' has empty type");
        }

        @Test
        void reportsChannelContextInErrors() {
            StreamYaml stream = validStream();
            stream.setType("");
            ConfigYaml config = config(server(8080, 1935), channels("/vod/{name}", stream));

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .hasMessage("channel '/vod/{name}' has empty type");
        }

        @Test
        void rejectsEnabledAllStreamsPlaylistWithoutPath() {
            ConfigYaml config = config(server(8080, 1935), new LinkedHashMap<>());
            config.getApplication().getAllStreamsPlaylist().setEnabled(true);
            config.getApplication().getAllStreamsPlaylist().setPath("");

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("all_streams_playlist is enabled but path is empty");
        }

        @Test
        void validatesAuthTokenTemplateForLiveChannels() {
            StreamYaml stream = validLiveStream();
            stream.setAuthTokenTemplate("{room_id}");
            ConfigYaml config = config(server(8080, 1935), channels("/user/{username}", stream));

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .hasMessage("channel '/user/{username}': auth_token_template references {room_id} "
                            + "but channel pattern doesn't contain it");
        }

        @Test
        void validatesRestreamChannelPlaceholders() {
            ConfigYaml config = config(server(8080, 1935), channels("/restream/{username}", validRestreamStream()));

            assertThatThrownBy(() -> validator.validateConfig(config))
                    .hasMessage("channel '/restream/{username}': restream channels must not contain "
                            + "user variable placeholders like {var} in channel name");
        }
    }

    // ------------------------------------------------------------ validateStream

    @Nested
    class ValidateStream {

        @Test
        void rejectsEmptyType() {
            StreamYaml stream = validStream();
            stream.setType("");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has empty type");
        }

        @Test
        void rejectsInvalidType() {
            StreamYaml stream = validStream();
            stream.setType("invalid_type");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has invalid type: invalid_type");
        }

        @Test
        void rejectsEmptyPath() {
            StreamYaml stream = validStream();
            stream.setPath("");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has empty path");
        }

        @Test
        void rejectsPathTraversal() {
            StreamYaml stream = validStream();
            stream.setPath("videos/../etc/passwd");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test path cannot contain '..' (path traversal attempt)");
        }

        @Test
        void rejectsViewersOnNonLiveStream() {
            StreamYaml stream = validStream();
            stream.setViewers(viewers(true, 30));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has viewers enabled but is not a live or restream stream "
                            + "(only live and restream streams support viewer tracking)");
        }

        @Test
        void rejectsViewersWithZeroWindow() {
            StreamYaml stream = validLiveStream();
            stream.setViewers(viewers(true, 0));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has invalid viewers window: must be > 0 (viewers require an expiry window)");
        }

        @Test
        void acceptsDisabledViewersOnNonLiveStream() {
            StreamYaml stream = validStream();
            stream.setViewers(viewers(false, 0));

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        @Test
        void rejectsNegativeViewsWindow() {
            StreamYaml stream = validStream();
            stream.setViews(views(true, -1));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has invalid views window: must be >= 0 (0 means instant count)");
        }

        @Test
        void acceptsZeroViewsWindow() {
            StreamYaml stream = validStream();
            stream.setViews(views(true, 0));

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        @Test
        void rejectsMissingLiveStreamKey() {
            StreamYaml stream = validLiveStream();
            stream.setLiveStreamKey("");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type live must have live_stream_key");
        }

        @Test
        void rejectsMissingAuthTokenTemplate() {
            StreamYaml stream = validLiveStream();
            stream.setAuthTokenTemplate("");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type live must have auth_token_template");
        }

        @Test
        void rejectsVideoInputPathOnLiveStream() {
            StreamYaml stream = validLiveStream();
            stream.setVideoInputPath("raw/test");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type live should not have video_input_path");
        }

        @Test
        void rejectsDeleteAfterEncodingOnLiveStream() {
            StreamYaml stream = validLiveStream();
            stream.setDeleteAfterEncoding(true);

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type live should not have delete_after_encoding enabled");
        }

        @Test
        void rejectsInvalidRecordPathOnLiveStream() {
            StreamYaml stream = validLiveStream();
            stream.setRecord(record(true, "/absolute/recordings"));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test record path should be a relative path, not absolute");
        }

        @Test
        void acceptsLiveStreamWithoutQualities() {
            StreamYaml stream = validLiveStream();
            stream.setQualities(new LinkedHashMap<>());

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        @Test
        void rejectsVideoUnencodedWithoutInputPath() {
            StreamYaml stream = new StreamYaml();
            stream.setType(StreamType.VIDEO_UNENCODED.value());
            stream.setPath("encoded/{name}");
            stream.setQualities(lowQuality());
            stream.setDistribution(distribution(hls(6, 3), null));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type video_unencoded must have video_input_path");
        }

        @Test
        void rejectsInvalidVideoInputPath() {
            StreamYaml stream = new StreamYaml();
            stream.setType(StreamType.VIDEO_UNENCODED.value());
            stream.setPath("encoded/{name}");
            stream.setVideoInputPath("raw/../etc");
            stream.setQualities(lowQuality());
            stream.setDistribution(distribution(hls(6, 3), null));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test video_input_path cannot contain '..' (path traversal attempt)");
        }

        @Test
        void rejectsRecordOnVideoUnencoded() {
            StreamYaml stream = new StreamYaml();
            stream.setType(StreamType.VIDEO_UNENCODED.value());
            stream.setPath("encoded/{name}");
            stream.setVideoInputPath("raw/{name}");
            stream.setRecord(record(true, ""));
            stream.setQualities(lowQuality());
            stream.setDistribution(distribution(hls(6, 3), null));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type video_unencoded should not have record enabled "
                            + "(only live streams support recording)");
        }

        @Test
        void rejectsRecordOnVideoEncoded() {
            StreamYaml stream = validStream();
            stream.setRecord(record(true, ""));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type video_encoded should not have record enabled "
                            + "(only live streams support recording)");
        }

        @Test
        void rejectsMissingQualitiesOnVideoEncoded() {
            StreamYaml stream = validStream();
            stream.setQualities(new LinkedHashMap<>());

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has no quality profiles defined");
        }

        @Test
        void reportsQualityNameInErrors() {
            StreamYaml stream = validStream();
            stream.getQualities().get("low").setWidth(0);

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test quality 'low' has invalid width: must be greater than 0");
        }

        @Test
        void rejectsThumbnailOnNonLiveStream() {
            StreamYaml stream = validStream();
            stream.setThumbnail(thumbnail(true, 5));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has thumbnail enabled but is not a live stream");
        }

        @Test
        void rejectsThumbnailWithZeroInterval() {
            StreamYaml stream = validLiveStream();
            stream.setThumbnail(thumbnail(true, 0));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has invalid thumbnail interval: must be > 0");
        }

        @Test
        void rejectsThumbnailWithNegativeInterval() {
            StreamYaml stream = validLiveStream();
            stream.setThumbnail(thumbnail(true, -1));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has invalid thumbnail interval: must be > 0");
        }

        @Test
        void acceptsThumbnailOnLiveStream() {
            StreamYaml stream = validLiveStream();
            stream.setThumbnail(thumbnail(true, 5));

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        @Test
        void ignoresIntervalWhenThumbnailDisabled() {
            StreamYaml stream = validLiveStream();
            stream.setThumbnail(thumbnail(false, 0));

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        /** A YAML key present with no children parses to null; validation must not blow up on it. */
        @Test
        void toleratesNullNestedBlocks() {
            StreamYaml stream = validLiveStream();
            stream.setRecord(null);
            stream.setViewers(null);
            stream.setViews(null);
            stream.setThumbnail(null);
            stream.setQualities(null);

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        @Test
        void rejectsNullDistributionBlock() {
            StreamYaml stream = validLiveStream();
            stream.setDistribution(null);

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test must have at least one distribution format (hls or dash)");
        }
    }

    // ----------------------------------------------------------- validateQuality

    @Nested
    class ValidateQuality {

        static List<Arguments> qualities() {
            return List.of(
                    Arguments.of("valid",
                            quality(1920, 1080, 30, "5000k", "libx264", "128k", "aac"), null),
                    Arguments.of("invalid width",
                            quality(0, 1080, 30, "5000k", "libx264", "128k", "aac"),
                            "test has invalid width: must be greater than 0"),
                    Arguments.of("invalid height",
                            quality(1920, 0, 30, "5000k", "libx264", "128k", "aac"),
                            "test has invalid height: must be greater than 0"),
                    Arguments.of("invalid framerate",
                            quality(1920, 1080, -1, "5000k", "libx264", "128k", "aac"),
                            "test has invalid framerate: must be greater than 0"),
                    Arguments.of("empty bitrate",
                            quality(1920, 1080, 30, "", "libx264", "128k", "aac"),
                            "test has empty bitrate"),
                    Arguments.of("empty codec",
                            quality(1920, 1080, 30, "5000k", "", "128k", "aac"),
                            "test has empty codec"),
                    Arguments.of("empty audio bitrate",
                            quality(1920, 1080, 30, "5000k", "libx264", "", "aac"),
                            "test has empty audio bitrate"),
                    Arguments.of("empty audio codec",
                            quality(1920, 1080, 30, "5000k", "libx264", "128k", ""),
                            "test has empty audio codec"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("qualities")
        void validatesQuality(String name, QualityYaml quality, String expectedError) {
            if (expectedError == null) {
                assertThatCode(() -> validator.validateQuality(quality, "test")).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> validator.validateQuality(quality, "test"))
                        .isInstanceOf(ConfigurationException.class)
                        .hasMessage(expectedError);
            }
        }

        @Test
        void toleratesNullAudioBlock() {
            QualityYaml quality = quality(1920, 1080, 30, "5000k", "libx264", "128k", "aac");
            quality.setAudio(null);

            assertThatThrownBy(() -> validator.validateQuality(quality, "test"))
                    .hasMessage("test has empty audio bitrate");
        }
    }

    // ------------------------------------------------------ validateDistribution

    @Nested
    class ValidateDistribution {

        static List<Arguments> distributions() {
            return List.of(
                    Arguments.of("valid hls only", distribution(hls(4, 3), null), null),
                    Arguments.of("invalid hls segment_duration", distribution(hls(0, 3), null),
                            "test has invalid HLS segment_duration: must be greater than 0"),
                    Arguments.of("negative hls window_size", distribution(hls(4, -1), null),
                            "test has invalid HLS window_size: must be 0 or greater (0 uses default of 3)"),
                    Arguments.of("zero hls window_size is valid", distribution(hls(4, 0), null), null),
                    Arguments.of("no distribution format", distribution(null, null),
                            "test must have at least one distribution format (hls or dash)"),
                    Arguments.of("valid dash only", distribution(null, dash(4, 3)), null),
                    Arguments.of("invalid dash segment_duration", distribution(null, dash(0, 3)),
                            "test has invalid DASH segment_duration: must be greater than 0"),
                    Arguments.of("negative dash window_size", distribution(null, dash(4, -1)),
                            "test has invalid DASH window_size: must be 0 or greater (0 uses default of 3)"),
                    Arguments.of("zero dash window_size is valid", distribution(null, dash(4, 0)), null),
                    Arguments.of("valid dual mode matching segment_duration and window_size",
                            distribution(hls(2, 5), dash(2, 5)), null),
                    Arguments.of("dual mode mismatched segment_duration",
                            distribution(hls(2, 3), dash(4, 3)),
                            "test has mismatched segment_duration between HLS (2) and DASH (4): "
                                    + "must be equal in dual mode"),
                    Arguments.of("dual mode mismatched window_size",
                            distribution(hls(2, 3), dash(2, 5)),
                            "test has mismatched window_size between HLS (3) and DASH (5): "
                                    + "must be equal in dual mode"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("distributions")
        void validatesDistribution(String name, DistributionYaml distribution, String expectedError) {
            if (expectedError == null) {
                assertThatCode(() -> validator.validateDistribution(distribution, "test"))
                        .doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> validator.validateDistribution(distribution, "test"))
                        .isInstanceOf(ConfigurationException.class)
                        .hasMessage(expectedError);
            }
        }

        @Test
        void treatsNullDistributionAsMissingFormat() {
            assertThatThrownBy(() -> validator.validateDistribution(null, "test"))
                    .hasMessage("test must have at least one distribution format (hls or dash)");
        }
    }

    // ------------------------------------------------- validateAuthTokenTemplate

    @Nested
    class ValidateAuthTokenTemplate {

        private static StreamYaml withTemplate(String template) {
            StreamYaml stream = new StreamYaml();
            stream.setAuthTokenTemplate(template);
            return stream;
        }

        static List<Arguments> templates() {
            return List.of(
                    Arguments.of("variable exists in pattern", "/user/{username}", "{username}", null),
                    Arguments.of("variable missing from pattern", "/user/{username}", "{room_id}",
                            "channel '/user/{username}': auth_token_template references {room_id} "
                                    + "but channel pattern doesn't contain it"),
                    Arguments.of("no variables in template", "/user/{username}", "static_token",
                            "channel '/user/{username}': auth_token_template must contain at least one {variable}"),
                    Arguments.of("multiple variables all present", "/room/{room_id}/{username}",
                            "{room_id}{username}", null));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("templates")
        void validatesAuthTokenTemplate(String name, String channelName, String template, String expectedError) {
            StreamYaml stream = withTemplate(template);
            if (expectedError == null) {
                assertThatCode(() -> validator.validateAuthTokenTemplate(channelName, stream))
                        .doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> validator.validateAuthTokenTemplate(channelName, stream))
                        .isInstanceOf(ConfigurationException.class)
                        .hasMessage(expectedError);
            }
        }

        /** {%FUNC%} builtins are not user variables, so a builtin-only template counts as having none. */
        @Test
        void rejectsBuiltinOnlyTemplate() {
            assertThatThrownBy(() ->
                    validator.validateAuthTokenTemplate("/user/{%UUID%}", withTemplate("{%UUID%}")))
                    .hasMessage("channel '/user/{%UUID%}': auth_token_template must contain at least one {variable}");
        }
    }

    // -------------------------------------------------- validateRestreamChannel

    @Nested
    class ValidateRestreamChannel {

        private static StreamYaml restream(String path, RecordYaml record) {
            StreamYaml stream = new StreamYaml();
            stream.setPath(path);
            if (record != null) {
                stream.setRecord(record);
            }
            return stream;
        }

        static List<Arguments> channels() {
            return List.of(
                    Arguments.of("valid literal channel", "/restream/mystream",
                            restream("restream/mystream", null), null),
                    Arguments.of("valid path with builtin function", "/restream/mystream",
                            restream("restream/mystream/{%STARTING_DATE%}", null), null),
                    Arguments.of("channel key with user variable", "/restream/{username}",
                            restream("restream/mystream", null),
                            "channel '/restream/{username}': restream channels must not contain "
                                    + "user variable placeholders like {var} in channel name"),
                    Arguments.of("path with user variable", "/restream/mystream",
                            restream("restream/{username}", null),
                            "channel '/restream/mystream': restream stream path must not contain "
                                    + "user variable placeholders like {var}"),
                    Arguments.of("record path with user variable", "/restream/mystream",
                            restream("restream/mystream", record(true, "recordings/{username}")),
                            "channel '/restream/mystream': restream record path must not contain "
                                    + "user variable placeholders like {var}"),
                    Arguments.of("record path with builtin only", "/restream/mystream",
                            restream("restream/mystream", record(true, "recordings/{%STARTING_DATE%}")), null),
                    Arguments.of("record path with user variable but recording disabled", "/restream/mystream",
                            restream("restream/mystream", record(false, "recordings/{username}")), null));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("channels")
        void validatesRestreamChannel(String name, String channelName, StreamYaml stream, String expectedError) {
            if (expectedError == null) {
                assertThatCode(() -> validator.validateRestreamChannel(channelName, stream))
                        .doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> validator.validateRestreamChannel(channelName, stream))
                        .isInstanceOf(ConfigurationException.class)
                        .hasMessage(expectedError);
            }
        }
    }

    // -------------------------------------------------------------- validatePath

    @Nested
    class ValidatePath {

        static List<Arguments> paths() {
            return List.of(
                    Arguments.of("valid path", "videos/stream1", null),
                    Arguments.of("valid path with placeholder", "videos/{name}", null),
                    Arguments.of("path traversal", "videos/../etc",
                            "test cannot contain '..' (path traversal attempt)"),
                    Arguments.of("absolute path unix", "/etc/passwd",
                            "test should be a relative path, not absolute"),
                    Arguments.of("absolute path windows", "\\windows\\system32",
                            "test should be a relative path, not absolute"),
                    Arguments.of("windows drive", "C:\\data",
                            "test should not contain Windows drive letters"),
                    Arguments.of("empty segments", "videos//stream1",
                            "test cannot contain empty segments"),
                    Arguments.of("dangerous null byte", "videos/%00test",
                            "test contains potentially dangerous character: %00"),
                    Arguments.of("dangerous pipe", "videos/test|cmd",
                            "test contains potentially dangerous character: |"),
                    Arguments.of("dangerous redirect", "videos/test>file",
                            "test contains potentially dangerous character: >"),
                    Arguments.of("dangerous wildcard", "videos/test*",
                            "test contains potentially dangerous character: *"),
                    Arguments.of("dangerous encoded dot", "videos/%2etest",
                            "test contains potentially dangerous character: %2e"),
                    Arguments.of("dangerous encoded slash", "videos/%2ftest",
                            "test contains potentially dangerous character: %2f"),
                    Arguments.of("dangerous encoded backslash", "videos/%5ctest",
                            "test contains potentially dangerous character: %5c"),
                    Arguments.of("dangerous less than", "videos/test<file",
                            "test contains potentially dangerous character: <"),
                    Arguments.of("dangerous question mark", "videos/test?",
                            "test contains potentially dangerous character: ?"),
                    Arguments.of("tilde is allowed", "videos/~backup", null),
                    Arguments.of("builtin function placeholder", "videos/{%STARTING_DATE%}", null));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("paths")
        void validatesPath(String name, String path, String expectedError) {
            if (expectedError == null) {
                assertThatCode(() -> validator.validatePath(path, "test")).doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> validator.validatePath(path, "test"))
                        .isInstanceOf(ConfigurationException.class)
                        .hasMessage(expectedError);
            }
        }
    }

    // ---------------------------------------------------- validateStream restream

    @Nested
    class ValidateRestreamStream {

        @Test
        void acceptsValidRestreamConfig() {
            assertThatCode(() -> validator.validateStream(validRestreamStream(), "test"))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsMissingSourceUrl() {
            StreamYaml stream = validRestreamStream();
            stream.setSourceUrl("");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type restream must have source_url");
        }

        @Test
        void rejectsLiveStreamKey() {
            StreamYaml stream = validRestreamStream();
            stream.setLiveStreamKey("secret");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type restream should not have live_stream_key");
        }

        @Test
        void rejectsAuthTokenTemplate() {
            StreamYaml stream = validRestreamStream();
            stream.setAuthTokenTemplate("{username}");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type restream should not have auth_token_template");
        }

        @Test
        void rejectsVideoInputPath() {
            StreamYaml stream = validRestreamStream();
            stream.setVideoInputPath("raw/test");

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type restream should not have video_input_path");
        }

        @Test
        void rejectsDeleteAfterEncoding() {
            StreamYaml stream = validRestreamStream();
            stream.setDeleteAfterEncoding(true);

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test of type restream should not have delete_after_encoding enabled");
        }

        @Test
        void acceptsQualities() {
            StreamYaml stream = validRestreamStream();
            stream.setQualities(lowQuality());

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        @Test
        void acceptsRecording() {
            StreamYaml stream = validRestreamStream();
            stream.setRecord(record(true, "recordings/mystream"));

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        @Test
        void acceptsViewers() {
            StreamYaml stream = validRestreamStream();
            stream.setViewers(viewers(true, 30));

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        @Test
        void acceptsViews() {
            StreamYaml stream = validRestreamStream();
            stream.setViews(views(true, 30));

            assertThatCode(() -> validator.validateStream(stream, "test")).doesNotThrowAnyException();
        }

        @Test
        void rejectsThumbnail() {
            StreamYaml stream = validRestreamStream();
            stream.setThumbnail(thumbnail(true, 5));

            assertThatThrownBy(() -> validator.validateStream(stream, "test"))
                    .hasMessage("test has thumbnail enabled but is not a live stream");
        }
    }

    // ---------------------------------------------------------------------- load

    @Nested
    class Load {

        @Test
        void loadsFullConfigFromFile(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    application:
                      public_path: "http://localhost:8080"
                    server:
                      http: 8080
                      rtmp: 1935
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
                          distribution:
                            hls:
                              segment_duration: 2
                              window_size: 3
                    """);

            LoadedConfiguration loaded = new YamlConfigFile().load(configPath);

            assertThat(loaded.application().publicPath()).isEqualTo("http://localhost:8080");
            assertThat(loaded.server().httpPort()).isEqualTo(8080);
            assertThat(loaded.server().rtmpPort()).isEqualTo(1935);
            assertThat(loaded.channels()).hasSize(1);

            Stream channel = loaded.channels().get("/user/{username}");
            assertThat(channel.type()).isEqualTo(StreamType.LIVE);
            assertThat(channel.liveStreamKey()).isEqualTo("secret");
            assertThat(channel.qualities()).containsOnlyKeys("low");
            assertThat(channel.distribution().hls().segmentDuration()).isEqualTo(2);
        }

        @Test
        void rejectsMissingFile() {
            assertThatThrownBy(() -> new YamlConfigFile().load(Path.of("/nonexistent/config.yml")))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageStartingWith("error reading config file:");
        }

        @Test
        void rejectsInvalidYaml(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, "{{invalid yaml");

            assertThatThrownBy(() -> new YamlConfigFile().load(configPath))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageStartingWith("error parsing config file:");
        }

        @Test
        void wrapsValidationErrors(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    server:
                      http: 0
                      rtmp: 1935
                    """);

            assertThatThrownBy(() -> new YamlConfigFile().load(configPath))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("invalid configuration: invalid HTTP port: must be greater than 0");
        }

        @Test
        void loadsDashOnlyConfig(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    application:
                      public_path: "http://localhost:8080"
                    server:
                      http: 8080
                      rtmp: 1935
                    channels:
                      "/user/{username}":
                        stream:
                          type: live
                          path: "live/{username}"
                          live_stream_key: "secret"
                          auth_token_template: "{username}"
                          distribution:
                            dash:
                              segment_duration: 4
                              window_size: 5
                    """);

            Stream channel = new YamlConfigFile().load(configPath).channels().get("/user/{username}");

            assertThat(channel.distribution().hls()).isNull();
            assertThat(channel.distribution().dash()).isNotNull();
            assertThat(channel.distribution().dash().segmentDuration()).isEqualTo(4);
            assertThat(channel.distribution().dash().windowSize()).isEqualTo(5);
        }

        @Test
        void loadsDualModeConfig(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    application:
                      public_path: "http://localhost:8080"
                    server:
                      http: 8080
                      rtmp: 1935
                    channels:
                      "/user/{username}":
                        stream:
                          type: live
                          path: "live/{username}"
                          live_stream_key: "secret"
                          auth_token_template: "{username}"
                          distribution:
                            hls:
                              segment_duration: 2
                              window_size: 3
                            dash:
                              segment_duration: 2
                              window_size: 3
                    """);

            Stream channel = new YamlConfigFile().load(configPath).channels().get("/user/{username}");

            assertThat(channel.distribution().hls()).isNotNull();
            assertThat(channel.distribution().dash()).isNotNull();
            assertThat(channel.distribution().hls().segmentDuration()).isEqualTo(2);
            assertThat(channel.distribution().dash().segmentDuration()).isEqualTo(2);
        }

        @Test
        void rejectsDualModeMismatchedWindowSize(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    application:
                      public_path: "http://localhost:8080"
                    server:
                      http: 8080
                      rtmp: 1935
                    channels:
                      "/user/{username}":
                        stream:
                          type: live
                          path: "live/{username}"
                          live_stream_key: "secret"
                          auth_token_template: "{username}"
                          distribution:
                            hls:
                              segment_duration: 2
                              window_size: 3
                            dash:
                              segment_duration: 2
                              window_size: 5
                    """);

            assertThatThrownBy(() -> new YamlConfigFile().load(configPath))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("mismatched window_size between HLS (3) and DASH (5)");
        }

        @Test
        void rejectsDualModeMismatchedSegmentDuration(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    application:
                      public_path: "http://localhost:8080"
                    server:
                      http: 8080
                      rtmp: 1935
                    channels:
                      "/user/{username}":
                        stream:
                          type: live
                          path: "live/{username}"
                          live_stream_key: "secret"
                          auth_token_template: "{username}"
                          distribution:
                            hls:
                              segment_duration: 2
                              window_size: 3
                            dash:
                              segment_duration: 4
                              window_size: 3
                    """);

            assertThatThrownBy(() -> new YamlConfigFile().load(configPath))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("mismatched segment_duration between HLS (2) and DASH (4)");
        }

        @Test
        void loadsRestreamConfig(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    application:
                      public_path: "http://localhost:8080"
                    server:
                      http: 8080
                      rtmp: 1935
                    channels:
                      "/restream/mystream":
                        stream:
                          type: restream
                          source_url: "rtmp://external-server/live/stream_key"
                          path: "restream/mystream"
                          distribution:
                            hls:
                              segment_duration: 2
                              window_size: 3
                    """);

            Stream channel = new YamlConfigFile().load(configPath).channels().get("/restream/mystream");

            assertThat(channel.type()).isEqualTo(StreamType.RESTREAM);
            assertThat(channel.sourceUrl()).isEqualTo("rtmp://external-server/live/stream_key");
        }

        @Test
        void rejectsRestreamWithUserVariableInPath(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    application:
                      public_path: "http://localhost:8080"
                    server:
                      http: 8080
                      rtmp: 1935
                    channels:
                      "/restream/{username}":
                        stream:
                          type: restream
                          source_url: "rtmp://external-server/live/stream_key"
                          path: "restream/{username}"
                          distribution:
                            hls:
                              segment_duration: 2
                              window_size: 3
                    """);

            assertThatThrownBy(() -> new YamlConfigFile().load(configPath))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("restream channels must not contain user variable placeholders");
        }

        /** A block written with no children parses to null; loading must not NPE on it. */
        @Test
        void toleratesPresentButEmptyBlocks(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    application:
                    server:
                      http: 8080
                      rtmp: 1935
                    channels:
                      "/user/{username}":
                        stream:
                          type: live
                          path: "live/{username}"
                          live_stream_key: "secret"
                          auth_token_template: "{username}"
                          record:
                          viewers:
                          views:
                          thumbnail:
                          qualities:
                          distribution:
                            hls:
                              segment_duration: 2
                    """);

            Stream channel = new YamlConfigFile().load(configPath).channels().get("/user/{username}");

            assertThat(channel.type()).isEqualTo(StreamType.LIVE);
            assertThat(channel.viewers().enabled()).isFalse();
            assertThat(channel.record().enabled()).isFalse();
            assertThat(channel.thumbnail().enabled()).isFalse();
            assertThat(channel.qualities()).isEmpty();
        }

        @Test
        void rejectsEmptyFile(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, "");

            assertThatThrownBy(() -> new YamlConfigFile().load(configPath))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("invalid configuration: invalid HTTP port: must be greater than 0");
        }

        /**
         * Anchors, aliases and merge keys are why loading goes through SnakeYAML before Jackson —
         * Jackson's YAMLFactory resolves none of them. The anchors here sit under an unknown
         * top-level key, exactly as the shipped configs write them: the key is ignored, the
         * anchors it defines still resolve.
         *
         * <p>Written inline on purpose. Asserting on {@code config.yml.example} would pin this
         * test to values that file is free to change — {@code ShippedConfigsTest} is what guards
         * the shipped files, and it asserts on their shape, not their contents.
         */
        @Test
        void resolvesAnchorsAliasesAndMergeKeys(@TempDir Path tempDir) throws IOException {
            Path configPath = writeConfig(tempDir, """
                    quality_profiles:
                      low: &LOW
                        width: 640
                        height: 360
                        framerate: 24
                        bitrate: "800k"
                        codec: "libx264"
                        audio:
                          bitrate: "96k"
                          codec: "aac"
                      high: &HIGH
                        width: 1920
                        height: 1080
                        framerate: 30
                        bitrate: "5000k"
                        codec: "libx264"
                        audio:
                          bitrate: "192k"
                          codec: "aac"

                    application:
                      public_path: "http://localhost:8080"
                      all_streams_playlist:
                        enabled: true
                        path: "all_streams.m3u8"

                    server:
                      http: 8080
                      rtmp: 1935

                    stream_templates:
                      live_base: &LIVE_BASE
                        stream:
                          type: live
                          path: "live/{username}"
                          live_stream_key: "testkey"
                          auth_token_template: "{username}"
                          qualities:
                            low: *LOW
                            high: *HIGH
                          distribution:
                            hls:
                              segment_duration: 2
                              window_size: 5

                    channels:
                      "/live/{username}":
                        <<: *LIVE_BASE
                    """);

            LoadedConfiguration loaded = new YamlConfigFile().load(configPath);

            assertThat(loaded.application().publicPath()).isEqualTo("http://localhost:8080");
            assertThat(loaded.application().allStreamsPlaylist().enabled()).isTrue();
            assertThat(loaded.application().allStreamsPlaylist().path()).isEqualTo("all_streams.m3u8");
            assertThat(loaded.server().httpPort()).isEqualTo(8080);
            assertThat(loaded.server().rtmpPort()).isEqualTo(1935);
            assertThat(loaded.server().rtmp().reconnectDelay()).isEqualTo(30);
            assertThat(loaded.server().rtmp().cleanupDelay()).isEqualTo(30);

            // The merge key pulled the whole template in, and the aliases resolved inside it
            assertThat(loaded.channels()).containsOnlyKeys("/live/{username}");

            Stream live = loaded.channels().get("/live/{username}");
            assertThat(live.type()).isEqualTo(StreamType.LIVE);
            assertThat(live.path()).isEqualTo("live/{username}");
            assertThat(live.authTokenTemplate()).isEqualTo("{username}");
            assertThat(live.qualities().keySet()).containsExactly("low", "high");
            assertThat(live.qualities().get("high").width()).isEqualTo(1920);
            assertThat(live.qualities().get("high").audio().bitrate()).isEqualTo("192k");
            assertThat(live.distribution().hls().segmentDuration()).isEqualTo(2);
            assertThat(live.distribution().hls().windowSize()).isEqualTo(5);
        }
    }
}
