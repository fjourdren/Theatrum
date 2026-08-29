package com.fjourdren.theatrum.application.port.in;

import com.fjourdren.theatrum.domain.model.StreamStats;
import com.fjourdren.theatrum.domain.model.Viewers;
import com.fjourdren.theatrum.domain.model.Views;

import java.util.List;

/** Concurrent-viewer and total-view accounting for a stream. */
public interface TrackViewerUseCase {

    /** Records that {@code clientIp} fetched a segment of the stream at {@code trackingKey}. */
    void trackSegmentRequest(String trackingKey, String clientIp, Viewers viewersCfg, Views viewsCfg);

    int getViewerCount(String trackingKey);

    long getViewCount(String trackingKey);

    List<StreamStats> getAllStreamStats();

    /** Drops all tracking state for a stream that has ended. */
    void unregisterStream(String trackingKey);
}
