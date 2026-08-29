package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.in.TrackViewerUseCase;
import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.StreamStats;
import com.fjourdren.theatrum.domain.model.Viewers;
import com.fjourdren.theatrum.domain.model.Views;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks concurrent viewers and total views per stream.
 *
 * <p>Both counters use a delayed window: a client IP is only counted once it has been requesting
 * segments continuously for {@code window} seconds. Total views are persisted to disk so they
 * survive restarts.
 */
@Component
@Slf4j
public class ViewerTracker implements TrackViewerUseCase {

    private static final long CLEANUP_INTERVAL_SECONDS = 10;

    private final Map<String, StreamTracking> streams = new ConcurrentHashMap<>();
    private final StoragePort storage;
    private final AppPaths appPaths;
    private final Clock clock;

    private ScheduledExecutorService cleanupExecutor;

    @Autowired
    public ViewerTracker(StoragePort storage, AppPaths appPaths) {
        this(storage, appPaths, Clock.systemUTC());
    }

    ViewerTracker(StoragePort storage, AppPaths appPaths, Clock clock) {
        this.storage = storage;
        this.appPaths = appPaths;
        this.clock = clock;
    }

    @PostConstruct
    public void startCleanupLoop() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("viewer-tracker-cleanup").factory());
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                runCleanupOnce();
            } catch (RuntimeException e) {
                log.error("Viewer tracker cleanup failed", e);
            }
        }, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stopCleanupLoop() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
    }

    /**
     * Called on every segment request. Refreshes viewer activity and counts a view once the
     * client has been watching for the configured window.
     */
    @Override
    public void trackSegmentRequest(String trackingKey, String clientIp, Viewers viewersCfg, Views viewsCfg) {
        StreamTracking st = getOrCreateStream(trackingKey, viewersCfg, viewsCfg);
        Instant now = clock.instant();

        synchronized (st) {
            if (st.viewersEnabled) {
                Session session = st.activeViewers.get(clientIp);
                if (session == null || elapsed(session.lastActive, now, st.viewerWindow)) {
                    session = new Session(now);
                    st.activeViewers.put(clientIp, session);
                }
                session.lastActive = now;
            }

            if (st.viewsEnabled) {
                Session session = st.viewSessions.get(clientIp);
                if (session == null || elapsed(session.lastActive, now, st.viewWindow)) {
                    session = new Session(now);
                    st.viewSessions.put(clientIp, session);
                }
                session.lastActive = now;
                if (!session.counted && elapsed(session.startedAt, now, st.viewWindow)) {
                    st.totalViews++;
                    st.dirty = true;
                    session.counted = true;
                }
            }
        }
    }

    /** Number of clients currently watching a stream. */
    @Override
    public int getViewerCount(String trackingKey) {
        StreamTracking st = streams.get(trackingKey);
        if (st == null) {
            return 0;
        }
        Instant now = clock.instant();
        synchronized (st) {
            return countViewers(st, now);
        }
    }

    /**
     * Total accumulated views for a stream. Falls back to the persisted count when the stream is
     * not tracked in memory.
     */
    @Override
    public long getViewCount(String trackingKey) {
        StreamTracking st = streams.get(trackingKey);
        if (st == null) {
            return loadViewCount(trackingKey);
        }
        synchronized (st) {
            return st.totalViews;
        }
    }

    /** Stats for all tracked streams. Used by the Prometheus collector on each scrape. */
    @Override
    public List<StreamStats> getAllStreamStats() {
        Instant now = clock.instant();
        List<StreamStats> stats = new ArrayList<>(streams.size());

        streams.forEach((key, st) -> {
            synchronized (st) {
                stats.add(new StreamStats(key, st.viewersEnabled ? countViewers(st, now) : 0, st.totalViews));
            }
        });

        return stats;
    }

    /** Drops tracking data when a stream ends, persisting the final view count first. */
    @Override
    public void unregisterStream(String trackingKey) {
        StreamTracking st = streams.remove(trackingKey);
        if (st == null || !st.viewsEnabled) {
            return;
        }

        long count;
        synchronized (st) {
            count = st.totalViews;
        }
        saveViewCount(trackingKey, count);
    }

    /** Expires stale sessions and flushes view counts changed since the last run. */
    void runCleanupOnce() {
        Instant now = clock.instant();
        Map<String, Long> toSave = new LinkedHashMap<>();

        streams.forEach((key, st) -> {
            synchronized (st) {
                st.activeViewers.values().removeIf(session -> elapsed(session.lastActive, now, st.viewerWindow));
                st.viewSessions.values().removeIf(session -> elapsed(session.lastActive, now, st.viewWindow));
                if (st.dirty && st.viewsEnabled) {
                    toSave.put(key, st.totalViews);
                    st.dirty = false;
                }
            }
        });

        // Save dirty view counts outside of the stream locks.
        toSave.forEach(this::saveViewCount);
    }

    private StreamTracking getOrCreateStream(String trackingKey, Viewers viewersCfg, Views viewsCfg) {
        return streams.computeIfAbsent(trackingKey,
                key -> new StreamTracking(viewersCfg, viewsCfg, loadViewCount(key)));
    }

    private static int countViewers(StreamTracking st, Instant now) {
        int count = 0;
        for (Session session : st.activeViewers.values()) {
            if (elapsed(session.startedAt, now, st.viewerWindow) && !elapsed(session.lastActive, now, st.viewerWindow)) {
                count++;
            }
        }
        return count;
    }

    private static boolean elapsed(Instant since, Instant now, Duration window) {
        return Duration.between(since, now).compareTo(window) >= 0;
    }

    private Path viewsFilePath(String trackingKey) {
        return appPaths.videoDir().resolve(trackingKey).resolve(VideoConstants.VIEWS_FILE);
    }

    /** Reads a persisted view count. Returns 0 when missing or unreadable. */
    private long loadViewCount(String trackingKey) {
        try {
            return Long.parseLong(new String(storage.readFile(viewsFilePath(trackingKey)), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveViewCount(String trackingKey, long count) {
        try {
            storage.writeFile(viewsFilePath(trackingKey), Long.toString(count).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error persisting view count for " + trackingKey + ": " + e);
        }
    }

    /** Per-stream tracking state. All fields are guarded by the instance monitor. */
    private static final class StreamTracking {
        private final boolean viewersEnabled;
        private final Duration viewerWindow;
        private final Map<String, Session> activeViewers = new HashMap<>();

        private final boolean viewsEnabled;
        private final Duration viewWindow;
        private final Map<String, Session> viewSessions = new HashMap<>();

        private long totalViews;
        private boolean dirty;

        private StreamTracking(Viewers viewersCfg, Views viewsCfg, long totalViews) {
            this.viewersEnabled = viewersCfg.enabled();
            this.viewerWindow = Duration.ofSeconds(viewersCfg.window());
            this.viewsEnabled = viewsCfg.enabled();
            this.viewWindow = Duration.ofSeconds(viewsCfg.window());
            this.totalViews = totalViews;
        }
    }

    /** A single client's watching session. {@code counted} is only used for view sessions. */
    private static final class Session {
        private final Instant startedAt;
        private Instant lastActive;
        private boolean counted;

        private Session(Instant startedAt) {
            this.startedAt = startedAt;
            this.lastActive = startedAt;
        }
    }
}
