package com.fjourdren.theatrum.application.port.in;

import com.fjourdren.theatrum.domain.model.Application;
import com.fjourdren.theatrum.domain.model.Server;
import com.fjourdren.theatrum.domain.model.Stream;

import java.util.Map;

/** Read access to the configured application, server and channels. */
public interface ResolveChannelUseCase {

    Application getApplication();

    Server getServer();

    /** All configured channels, keyed by their URL pattern, in configuration order. */
    Map<String, Stream> getChannels();

    /** The channel registered under {@code channelId}, or {@code null} if there is none. */
    Stream getChannel(String channelId);

    /** Builds the aggregated M3U8 playlist listing every channel's master playlist. */
    String buildAllStreamsPlaylist();
}
