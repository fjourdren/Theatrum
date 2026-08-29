package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp;

import java.util.List;

public interface RtmpLifecycle {

    /** Starts the RTMP server. Returns once it is listening. */
    void startRtmpServer();

    void shutdownRtmpServer();

    List<String> getActiveStreams();
}
