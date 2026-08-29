package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.in.ResolveChannelUseCase;
import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.Application;
import com.fjourdren.theatrum.domain.model.FileMatch;
import com.fjourdren.theatrum.domain.model.LoadedConfiguration;
import com.fjourdren.theatrum.domain.model.Server;
import com.fjourdren.theatrum.domain.model.Stream;
import com.fjourdren.theatrum.domain.util.UrlUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Application-wide operations and configuration.
 *
 * <p>{@link LoadedConfiguration} is parsed before the context exists and registered as a
 * pre-existing singleton by {@code TheatrumApplication}, so it injects like any other bean.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApplicationService implements ResolveChannelUseCase {

    private final LoadedConfiguration configuration;
    private final StoragePort storage;
    private final PathTemplateService templateService;
    private final AppPaths appPaths;

    @Override
    public Application getApplication() {
        return configuration.application();
    }

    @Override
    public Server getServer() {
        return configuration.server();
    }

    @Override
    public Map<String, Stream> getChannels() {
        return configuration.channels();
    }

    /** @throws IllegalArgumentException when no channel is configured under {@code channelId} */
    @Override
    public Stream getChannel(String channelId) {
        Stream channel = getChannels().get(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("channel not found: " + channelId);
        }
        return channel;
    }

    /**
     * Builds an M3U8 playlist listing the master playlist of every available stream.
     *
     * @throws IllegalStateException when the all-streams playlist is disabled
     */
    @Override
    public String buildAllStreamsPlaylist() {
        if (!getApplication().allStreamsPlaylist().enabled()) {
            throw new IllegalStateException("all streams playlist is not enabled");
        }

        var playlist = new StringBuilder("""
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-STREAM-INF:BANDWIDTH=0
                """);

        // Search for the master playlist file of each stream.
        for (var entry : getChannels().entrySet()) {
            Stream stream = entry.getValue();
            String pattern = appPaths.videoDir().resolve(stream.getMasterPlaylistTemplatePath()).toString();

            List<FileMatch> matches;
            try {
                matches = storage.searchFiles(pattern, VideoConstants.VALID_MASTER_PLAYLIST_EXTENSIONS);
            } catch (Exception e) {
                log.error("Error searching for videos in {}: {}", stream.path(), e.getMessage());
                continue;
            }

            for (FileMatch match : matches) {
                String channelPath;
                try {
                    channelPath = templateService.replacePlaceholders(entry.getKey(), match.vars());
                } catch (RuntimeException e) {
                    log.error("Error replacing placeholders: {}", e.getMessage());
                    continue;
                }

                String masterFilePublicPath = UrlUtils.joinUrl(
                        getApplication().publicPath(), channelPath, VideoConstants.MASTER_PLAYLIST);

                log.info("Adding master playlist: {}", masterFilePublicPath);
                playlist.append(masterFilePublicPath).append('\n');
            }
        }

        return playlist.toString();
    }
}
