package com.fjourdren.theatrum.infrastructure.adapter.out.config;

import com.fjourdren.theatrum.domain.constant.ConfigConstants;
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
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.Map;

/**
 * Maps YAML configuration entities to domain models.
 *
 * <p>Every block is straight-through: the entities carry their own zero values as field
 * initializers and {@link YamlConfigFile} parses with {@code Nulls.SKIP}, so a block written with
 * nothing under it arrives here as an empty entity rather than {@code null}. That is what keeps
 * this a plain field mapping — the only expressions left are the two defaults that are a *value*
 * rather than an empty block. Callers are expected to have parsed and validated the tree first;
 * the methods keep MapStruct's plain {@code null in, null out} contract.
 *
 * <p>{@code disableBuilder} keeps MapStruct on the records' canonical constructors instead of
 * {@link Stream}'s hand-written builder, whose {@code hls}/{@code dash}/{@code quality}
 * convenience setters have no source counterpart.
 */
@Mapper(
        builder = @Builder(disableBuilder = true),
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        imports = ConfigConstants.class)
public interface ConfigMapper {

    /**
     * Handle on the {@code ConfigMapperImpl} MapStruct generates at compile time — the interface
     * itself has no code. Deliberately not a Spring bean: the only production caller,
     * {@link YamlConfigFile}, is constructed by hand in {@code TheatrumApplication} before the
     * context exists, so nothing could be injected there. The generated mapper is stateless, so
     * one shared instance is safe.
     */
    ConfigMapper INSTANCE = Mappers.getMapper(ConfigMapper.class);

    // ----------------------------------------------------------------------- root

    /** Maps a parsed and validated {@code config.yml}. */
    LoadedConfiguration toDomainConfiguration(ConfigYaml config);

    // ------------------------------------------------------------------ application

    Application toDomainApplication(ApplicationYaml application);

    AllStreamsPlaylist toDomainAllStreamsPlaylist(AllStreamsPlaylistYaml playlist);

    // ----------------------------------------------------------------------- server

    Server toDomainServer(ServerYaml server);

    @Mapping(target = "reconnectDelay", expression = "java(rtmp.getReconnectDelay() <= 0"
            + " ? ConfigConstants.DEFAULT_RTMP_DELAY : rtmp.getReconnectDelay())")
    @Mapping(target = "cleanupDelay", expression = "java(rtmp.getCleanupDelay() <= 0"
            + " ? ConfigConstants.DEFAULT_RTMP_DELAY : rtmp.getCleanupDelay())")
    Rtmp toDomainRtmp(RtmpYaml rtmp);

    // ---------------------------------------------------------------------- quality

    Quality toDomainQuality(QualityYaml quality);

    Audio toDomainAudio(AudioYaml audio);

    // ----------------------------------------------------------------- distribution

    Distribution toDomainDistribution(DistributionYaml distribution);

    @Mapping(target = "windowSize", expression = "java(hls.getWindowSize() <= 0"
            + " ? ConfigConstants.DEFAULT_WINDOW_SIZE : hls.getWindowSize())")
    Hls toDomainHls(HlsYaml hls);

    @Mapping(target = "windowSize", expression = "java(dash.getWindowSize() <= 0"
            + " ? ConfigConstants.DEFAULT_WINDOW_SIZE : dash.getWindowSize())")
    Dash toDomainDash(DashYaml dash);

    // ----------------------------------------------------------------------- stream

    Stream toDomainStream(StreamYaml stream);

    Record toDomainRecord(RecordYaml record);

    Viewers toDomainViewers(ViewersYaml viewers);

    Views toDomainViews(ViewsYaml views);

    Thumbnail toDomainThumbnail(ThumbnailYaml thumbnail);

    /** Unknown values map to {@code null}, mirroring Go's untyped string cast. */
    default StreamType toDomainStreamType(String type) {
        return StreamType.fromValue(type);
    }

    // --------------------------------------------------------------------- channels

    /**
     * Converts YAML channels to domain streams, keeping configuration order so FFmpeg arguments
     * built from them are deterministic (MapStruct targets a {@code LinkedHashMap}).
     */
    Map<String, Stream> toDomainChannels(Map<String, ChannelYaml> channels);

    /** A channel is a thin wrapper; only its stream reaches the domain. */
    default Stream toDomainStream(ChannelYaml channel) {
        StreamYaml stream = channel == null ? null : channel.getStream();
        return toDomainStream(stream == null ? new StreamYaml() : stream);
    }
}
