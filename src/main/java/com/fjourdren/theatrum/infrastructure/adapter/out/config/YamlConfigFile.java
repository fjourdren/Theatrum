package com.fjourdren.theatrum.infrastructure.adapter.out.config;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fjourdren.theatrum.application.port.out.exception.ConfigurationException;
import com.fjourdren.theatrum.application.port.out.ConfigurationPort;
import com.fjourdren.theatrum.domain.constant.PathConstants;
import com.fjourdren.theatrum.domain.constant.TemplateConstants;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.domain.model.StreamType;
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
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ServerYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.StreamTemplateYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.StreamYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ThumbnailYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ViewersYaml;
import com.fjourdren.theatrum.infrastructure.adapter.out.config.entities.ViewsYaml;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;

/** {@link ConfigurationPort} backed by a YAML file. */
@Component
public class YamlConfigFile implements ConfigurationPort {

    /**
     * A key written with nothing under it hands Jackson an explicit null, which would overwrite the
     * entity's field initializer. Skipping those nulls keeps the initializer, so every block below
     * the root is non-null once parsed — which is why {@link ConfigMapper} can map straight through
     * instead of carrying a default for each one.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .defaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.SKIP))
            .build();

    @Override
    public LoadedConfiguration load(Path configPath) {
        String data;
        try {
            data = Files.readString(configPath);
        } catch (IOException e) {
            throw new ConfigurationException("error reading config file: " + e, e);
        }

        ConfigYaml config = parse(data);

        try {
            validateConfig(config);
        } catch (ConfigurationException e) {
            throw new ConfigurationException("invalid configuration: " + e.getMessage(), e);
        }

        return ConfigMapper.INSTANCE.toDomainConfiguration(config);
    }

    /**
     * SnakeYAML resolves anchors, aliases and merge keys, which Jackson's YAMLFactory does not;
     * the resolved tree is then bound to the annotated entities by Jackson.
     */
    ConfigYaml parse(String data) {
        try {
            Object tree = new Yaml(new SafeConstructor(new LoaderOptions())).load(data);
            return tree == null ? new ConfigYaml() : MAPPER.convertValue(tree, ConfigYaml.class);
        } catch (RuntimeException e) {
            throw new ConfigurationException("error parsing config file: " + e, e);
        }
    }

    // --------------------------------------------------------------- validation

    void validateConfig(ConfigYaml config) {
        // Validate application configuration
        ApplicationYaml application = config.getApplication() == null
                ? new ApplicationYaml()
                : config.getApplication();
        AllStreamsPlaylistYaml playlist = application.getAllStreamsPlaylist() == null
                ? new AllStreamsPlaylistYaml()
                : application.getAllStreamsPlaylist();
        if (playlist.isEnabled() && orEmpty(playlist.getPath()).isEmpty()) {
            throw new ConfigurationException("all_streams_playlist is enabled but path is empty");
        }

        // Validate server configuration
        ServerYaml server = config.getServer() == null ? new ServerYaml() : config.getServer();
        if (server.getHttpPort() <= 0) {
            throw new ConfigurationException("invalid HTTP port: must be greater than 0");
        }
        if (server.getRtmpPort() <= 0) {
            throw new ConfigurationException("invalid RTMP port: must be greater than 0");
        }

        // Validate stream templates
        Map<String, StreamTemplateYaml> templates = config.getStreamTemplates();
        if (templates != null) {
            for (Map.Entry<String, StreamTemplateYaml> entry : templates.entrySet()) {
                String name = entry.getKey();
                if (name == null || name.isEmpty() || name.equals("/")) {
                    throw new ConfigurationException("invalid template name: must not be '/'");
                }
                StreamTemplateYaml template = entry.getValue();
                validateStream(template == null ? null : template.getStream(), "template '" + name + "'");
            }
        }

        // Validate channels
        Map<String, ChannelYaml> channels = config.getChannels();
        if (channels != null) {
            for (Map.Entry<String, ChannelYaml> entry : channels.entrySet()) {
                String name = entry.getKey();
                if (name == null || name.isEmpty() || name.equals("/")) {
                    throw new ConfigurationException("invalid channel name: must not be '/'");
                }

                ChannelYaml channel = entry.getValue();
                StreamYaml stream = channel == null ? new StreamYaml() : orEmpty(channel.getStream());
                validateStream(stream, "channel '" + name + "'");

                // Live streams require auth_token_template variables to exist in the channel pattern
                if (StreamType.LIVE.value().equals(stream.getType())) {
                    validateAuthTokenTemplate(name, stream);
                }

                // Restream channels must not contain user variable placeholders
                if (StreamType.RESTREAM.value().equals(stream.getType())) {
                    validateRestreamChannel(name, stream);
                }
            }
        }
    }

    void validateStream(StreamYaml streamYaml, String context) {
        StreamYaml stream = orEmpty(streamYaml);
        RecordYaml record = stream.getRecord() == null ? new RecordYaml() : stream.getRecord();
        ViewersYaml viewers = stream.getViewers() == null ? new ViewersYaml() : stream.getViewers();
        ViewsYaml views = stream.getViews() == null ? new ViewsYaml() : stream.getViews();
        ThumbnailYaml thumbnail = stream.getThumbnail() == null ? new ThumbnailYaml() : stream.getThumbnail();

        String rawType = orEmpty(stream.getType());
        if (rawType.isEmpty()) {
            throw new ConfigurationException(context + " has empty type");
        }
        StreamType type = StreamType.fromValue(rawType);
        if (type == null) {
            throw new ConfigurationException(context + " has invalid type: " + rawType);
        }

        String path = orEmpty(stream.getPath());
        if (path.isEmpty()) {
            throw new ConfigurationException(context + " has empty path");
        }
        validatePath(path, context + " path");

        // Viewer tracking is only meaningful for streams that are running now
        boolean liveOrRestream = type == StreamType.LIVE || type == StreamType.RESTREAM;
        if (viewers.isEnabled() && !liveOrRestream) {
            throw new ConfigurationException(context + " has viewers enabled but is not a live or restream stream"
                    + " (only live and restream streams support viewer tracking)");
        }
        if (viewers.isEnabled() && viewers.getWindow() <= 0) {
            throw new ConfigurationException(context
                    + " has invalid viewers window: must be > 0 (viewers require an expiry window)");
        }

        if (views.getWindow() < 0) {
            throw new ConfigurationException(context
                    + " has invalid views window: must be >= 0 (0 means instant count)");
        }

        if (thumbnail.isEnabled() && type != StreamType.LIVE) {
            throw new ConfigurationException(context + " has thumbnail enabled but is not a live stream");
        }
        if (thumbnail.isEnabled() && thumbnail.getInterval() <= 0) {
            throw new ConfigurationException(context + " has invalid thumbnail interval: must be > 0");
        }

        switch (type) {
            case VIDEO_UNENCODED -> {
                String videoInputPath = orEmpty(stream.getVideoInputPath());
                if (videoInputPath.isEmpty()) {
                    throw new ConfigurationException(context + " of type video_unencoded must have video_input_path");
                }
                validatePath(videoInputPath, context + " video_input_path");

                // delete_after_encoding is valid here; recording is not
                if (record.isEnabled()) {
                    throw new ConfigurationException(context + " of type video_unencoded should not have record"
                            + " enabled (only live streams support recording)");
                }
            }
            case LIVE -> {
                if (orEmpty(stream.getLiveStreamKey()).isEmpty()) {
                    throw new ConfigurationException(context + " of type live must have live_stream_key");
                }
                if (orEmpty(stream.getAuthTokenTemplate()).isEmpty()) {
                    throw new ConfigurationException(context + " of type live must have auth_token_template");
                }
                if (!orEmpty(stream.getVideoInputPath()).isEmpty()) {
                    throw new ConfigurationException(context + " of type live should not have video_input_path");
                }
                if (stream.isDeleteAfterEncoding()) {
                    throw new ConfigurationException(context
                            + " of type live should not have delete_after_encoding enabled");
                }
                validateRecordPath(record, context);
            }
            case RESTREAM -> {
                if (orEmpty(stream.getSourceUrl()).isEmpty()) {
                    throw new ConfigurationException(context + " of type restream must have source_url");
                }
                if (!orEmpty(stream.getLiveStreamKey()).isEmpty()) {
                    throw new ConfigurationException(context + " of type restream should not have live_stream_key");
                }
                if (!orEmpty(stream.getAuthTokenTemplate()).isEmpty()) {
                    throw new ConfigurationException(context
                            + " of type restream should not have auth_token_template");
                }
                if (!orEmpty(stream.getVideoInputPath()).isEmpty()) {
                    throw new ConfigurationException(context + " of type restream should not have video_input_path");
                }
                if (stream.isDeleteAfterEncoding()) {
                    throw new ConfigurationException(context
                            + " of type restream should not have delete_after_encoding enabled");
                }
                validateRecordPath(record, context);
            }
            default -> {
                if (record.isEnabled()) {
                    throw new ConfigurationException(context + " of type " + rawType + " should not have record"
                            + " enabled (only live streams support recording)");
                }
            }
        }

        // Qualities are optional only for streams FFmpeg can pass through
        Map<String, QualityYaml> qualities = stream.getQualities();
        if (!liveOrRestream && (qualities == null || qualities.isEmpty())) {
            throw new ConfigurationException(context + " has no quality profiles defined");
        }
        if (qualities != null) {
            qualities.forEach((name, quality) -> validateQuality(quality, context + " quality '" + name + "'"));
        }

        validateDistribution(stream.getDistribution(), context);
    }

    void validateQuality(QualityYaml qualityYaml, String context) {
        QualityYaml quality = qualityYaml == null ? new QualityYaml() : qualityYaml;
        AudioYaml audio = quality.getAudio() == null ? new AudioYaml() : quality.getAudio();

        if (quality.getWidth() <= 0) {
            throw new ConfigurationException(context + " has invalid width: must be greater than 0");
        }
        if (quality.getHeight() <= 0) {
            throw new ConfigurationException(context + " has invalid height: must be greater than 0");
        }
        if (quality.getFramerate() <= 0) {
            throw new ConfigurationException(context + " has invalid framerate: must be greater than 0");
        }
        if (orEmpty(quality.getBitrate()).isEmpty()) {
            throw new ConfigurationException(context + " has empty bitrate");
        }
        if (orEmpty(quality.getCodec()).isEmpty()) {
            throw new ConfigurationException(context + " has empty codec");
        }
        if (orEmpty(audio.getBitrate()).isEmpty()) {
            throw new ConfigurationException(context + " has empty audio bitrate");
        }
        if (orEmpty(audio.getCodec()).isEmpty()) {
            throw new ConfigurationException(context + " has empty audio codec");
        }
    }

    void validateDistribution(DistributionYaml distributionYaml, String context) {
        DistributionYaml distribution = distributionYaml == null ? new DistributionYaml() : distributionYaml;
        HlsYaml hls = distribution.getHls();
        DashYaml dash = distribution.getDash();

        if (hls == null && dash == null) {
            throw new ConfigurationException(context + " must have at least one distribution format (hls or dash)");
        }

        if (hls != null) {
            if (hls.getSegmentDuration() <= 0) {
                throw new ConfigurationException(context + " has invalid HLS segment_duration: must be greater than 0");
            }
            if (hls.getWindowSize() < 0) {
                throw new ConfigurationException(context
                        + " has invalid HLS window_size: must be 0 or greater (0 uses default of 3)");
            }
        }

        if (dash != null) {
            if (dash.getSegmentDuration() <= 0) {
                throw new ConfigurationException(context
                        + " has invalid DASH segment_duration: must be greater than 0");
            }
            if (dash.getWindowSize() < 0) {
                throw new ConfigurationException(context
                        + " has invalid DASH window_size: must be 0 or greater (0 uses default of 3)");
            }
        }

        // Dual mode shares one FFmpeg process, so both formats must agree on segmenting
        if (hls != null && dash != null) {
            if (hls.getSegmentDuration() != dash.getSegmentDuration()) {
                throw new ConfigurationException(context + " has mismatched segment_duration between HLS ("
                        + hls.getSegmentDuration() + ") and DASH (" + dash.getSegmentDuration()
                        + "): must be equal in dual mode");
            }
            if (hls.getWindowSize() != dash.getWindowSize()) {
                throw new ConfigurationException(context + " has mismatched window_size between HLS ("
                        + hls.getWindowSize() + ") and DASH (" + dash.getWindowSize()
                        + "): must be equal in dual mode");
            }
        }
    }

    void validateAuthTokenTemplate(String channelName, StreamYaml streamYaml) {
        String template = orEmpty(orEmpty(streamYaml).getAuthTokenTemplate());

        Matcher matcher = TemplateConstants.IDENTIFIER_VAR_REGEX.matcher(template);
        boolean hasVariable = false;
        while (matcher.find()) {
            hasVariable = true;
            String variable = matcher.group(1);
            if (!channelName.contains("{" + variable + "}")) {
                throw new ConfigurationException("channel '" + channelName + "': auth_token_template references {"
                        + variable + "} but channel pattern doesn't contain it");
            }
        }

        if (!hasVariable) {
            throw new ConfigurationException("channel '" + channelName
                    + "': auth_token_template must contain at least one {variable}");
        }
    }

    /**
     * Restream channels have no incoming URL to extract variables from, so only {@code {%FUNC%}}
     * builtins may appear in the channel name, stream path and record path.
     */
    void validateRestreamChannel(String channelName, StreamYaml streamYaml) {
        StreamYaml stream = orEmpty(streamYaml);
        RecordYaml record = stream.getRecord() == null ? new RecordYaml() : stream.getRecord();

        if (TemplateConstants.NON_FUNC_VAR_REGEX.matcher(channelName).find()) {
            throw new ConfigurationException("channel '" + channelName + "': restream channels must not contain"
                    + " user variable placeholders like {var} in channel name");
        }
        if (TemplateConstants.NON_FUNC_VAR_REGEX.matcher(orEmpty(stream.getPath())).find()) {
            throw new ConfigurationException("channel '" + channelName + "': restream stream path must not contain"
                    + " user variable placeholders like {var}");
        }
        if (record.isEnabled() && !orEmpty(record.getPath()).isEmpty()
                && TemplateConstants.NON_FUNC_VAR_REGEX.matcher(record.getPath()).find()) {
            throw new ConfigurationException("channel '" + channelName + "': restream record path must not contain"
                    + " user variable placeholders like {var}");
        }
    }

    void validatePath(String pathValue, String context) {
        String path = orEmpty(pathValue);

        if (path.contains("..")) {
            throw new ConfigurationException(context + " cannot contain '..' (path traversal attempt)");
        }
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw new ConfigurationException(context + " should be a relative path, not absolute");
        }
        if (path.length() >= 2 && path.charAt(1) == ':') {
            throw new ConfigurationException(context + " should not contain Windows drive letters");
        }

        // -1 keeps trailing empty segments, matching Go's strings.Split
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new ConfigurationException(context + " cannot contain empty segments");
            }
        }

        for (String dangerous : PathConstants.DANGEROUS_IN_CONFIG_PATH) {
            if (path.contains(dangerous)) {
                throw new ConfigurationException(context + " contains potentially dangerous character: " + dangerous);
            }
        }
    }

    private void validateRecordPath(RecordYaml record, String context) {
        if (record.isEnabled() && !orEmpty(record.getPath()).isEmpty()) {
            validatePath(record.getPath(), context + " record path");
        }
    }

    /** A YAML key present with no children parses to null; Go saw a zero value there. */
    private static StreamYaml orEmpty(StreamYaml stream) {
        return stream == null ? new StreamYaml() : stream;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
