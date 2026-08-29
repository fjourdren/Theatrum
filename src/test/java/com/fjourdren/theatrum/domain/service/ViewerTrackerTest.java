package com.fjourdren.theatrum.domain.service;

import com.fjourdren.theatrum.application.port.out.StoragePort;
import com.fjourdren.theatrum.domain.constant.VideoConstants;
import com.fjourdren.theatrum.domain.model.AppPaths;
import com.fjourdren.theatrum.domain.model.FileMatch;
import com.fjourdren.theatrum.domain.model.StreamStats;
import com.fjourdren.theatrum.domain.model.Viewers;
import com.fjourdren.theatrum.domain.model.Views;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.tuple;

class ViewerTrackerTest {

    private static final String ALICE = "live/alice";
    private static final String BOB = "live/bob";

    @TempDir
    Path videoDir;

    private FakeStorage storage;
    private MutableClock clock;
    private ViewerTracker tracker;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        clock = new MutableClock();
        tracker = new ViewerTracker(storage, new AppPaths(videoDir, videoDir.resolve("frontend")), clock);
    }

    // ---- helpers ----

    private Path viewsFile(String trackingKey) {
        return videoDir.resolve(trackingKey).resolve(VideoConstants.VIEWS_FILE);
    }

    private void seedDisk(String trackingKey, String content) {
        storage.files.put(viewsFile(trackingKey), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Requests segments for every IP three times, keeping each session alive (requests are spaced
     * half a window apart) until exactly {@code window} has elapsed since the session started.
     * Afterwards every IP satisfies the counting condition: startedAt >= window ago, lastActive now.
     */
    private void watchFullWindow(String trackingKey, List<String> clientIps, Viewers viewersCfg, Views viewsCfg,
                                 int windowSeconds) {
        var halfWindow = Duration.ofMillis(windowSeconds * 500L);
        for (int step = 0; step < 3; step++) {
            clientIps.forEach(ip -> tracker.trackSegmentRequest(trackingKey, ip, viewersCfg, viewsCfg));
            if (step < 2) {
                clock.advance(halfWindow);
            }
        }
    }

    // ---- getAllStreamStats ----

    @Test
    void getAllStreamStatsEmpty() {
        assertThat(tracker.getAllStreamStats()).isEmpty();
    }

    @Test
    void getAllStreamStatsSingleStream() {
        seedDisk(ALICE, "10");
        watchFullWindow(ALICE, List.of("1.1.1.1", "2.2.2.2"), new Viewers(true, 5), Views.disabled(), 5);

        assertThat(tracker.getAllStreamStats())
                .extracting(StreamStats::trackingKey, StreamStats::viewers, StreamStats::views)
                .containsExactly(tuple(ALICE, 2, 10L));
    }

    @Test
    void getAllStreamStatsMultipleStreams() {
        seedDisk(ALICE, "3");
        seedDisk(BOB, "7");
        var viewersCfg = new Viewers(true, 5);
        var viewsCfg = Views.disabled();

        for (int step = 0; step < 3; step++) {
            tracker.trackSegmentRequest(ALICE, "1.1.1.1", viewersCfg, viewsCfg);
            tracker.trackSegmentRequest(BOB, "2.2.2.2", viewersCfg, viewsCfg);
            tracker.trackSegmentRequest(BOB, "3.3.3.3", viewersCfg, viewsCfg);
            if (step < 2) {
                clock.advance(Duration.ofMillis(2500));
            }
        }

        assertThat(tracker.getAllStreamStats())
                .extracting(StreamStats::trackingKey, StreamStats::viewers, StreamStats::views)
                .containsExactlyInAnyOrder(tuple(ALICE, 1, 3L), tuple(BOB, 2, 7L));
    }

    @Test
    void getAllStreamStatsWindowThresholdNotReached() {
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", new Viewers(true, 1), new Views(true, 1));

        assertThat(tracker.getAllStreamStats())
                .extracting(StreamStats::viewers, StreamStats::views)
                .containsExactly(tuple(0, 0L));
    }

    @Test
    void getAllStreamStatsWindowThresholdElapsed() {
        seedDisk(ALICE, "5");
        watchFullWindow(ALICE, List.of("1.1.1.1"), new Viewers(true, 1), Views.disabled(), 1);

        assertThat(tracker.getAllStreamStats())
                .extracting(StreamStats::viewers, StreamStats::views)
                .containsExactly(tuple(1, 5L));
    }

    @Test
    void getAllStreamStatsViewersDisabled() {
        seedDisk(ALICE, "42");
        watchFullWindow(ALICE, List.of("1.1.1.1"), Viewers.disabled(), new Views(true, 5), 5);

        assertThat(tracker.getAllStreamStats())
                .extracting(StreamStats::viewers, StreamStats::views)
                .containsExactly(tuple(0, 43L));
    }

    @Test
    void getAllStreamStatsAfterUnregister() {
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", new Viewers(true, 1), new Views(true, 1));
        tracker.unregisterStream(ALICE);

        assertThat(tracker.getAllStreamStats()).isEmpty();
    }

    // ---- trackSegmentRequest ----

    @Test
    void trackSegmentRequestNewSession() {
        watchFullWindow(ALICE, List.of("1.1.1.1"), new Viewers(true, 5), new Views(true, 5), 5);

        assertThat(tracker.getViewerCount(ALICE)).isEqualTo(1);
        assertThat(tracker.getViewCount(ALICE)).isEqualTo(1);
    }

    @Test
    void trackSegmentRequestExistingSessionIsReused() {
        var viewersCfg = new Viewers(true, 5);
        var viewsCfg = new Views(true, 5);
        watchFullWindow(ALICE, List.of("1.1.1.1"), viewersCfg, viewsCfg, 5);
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", viewersCfg, viewsCfg);

        // Same IP, same session: still one viewer and one view.
        assertThat(tracker.getViewerCount(ALICE)).isEqualTo(1);
        assertThat(tracker.getViewCount(ALICE)).isEqualTo(1);
    }

    @Test
    void trackSegmentRequestViewCountedAfterWindow() {
        watchFullWindow(ALICE, List.of("1.1.1.1"), new Viewers(true, 1), new Views(true, 1), 1);

        assertThat(tracker.getViewCount(ALICE)).isEqualTo(1);
    }

    @Test
    void trackSegmentRequestViewCountedOnlyOnce() {
        var viewersCfg = new Viewers(true, 1);
        var viewsCfg = new Views(true, 1);
        watchFullWindow(ALICE, List.of("1.1.1.1"), viewersCfg, viewsCfg, 1);

        // Keep the same session alive: the view must not be counted a second time.
        clock.advance(Duration.ofMillis(500));
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", viewersCfg, viewsCfg);
        clock.advance(Duration.ofMillis(500));
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", viewersCfg, viewsCfg);

        assertThat(tracker.getViewCount(ALICE)).isEqualTo(1);
    }

    @Test
    void trackSegmentRequestNewSessionAfterInactivityCountsAgain() {
        var viewersCfg = new Viewers(true, 1);
        var viewsCfg = new Views(true, 1);
        watchFullWindow(ALICE, List.of("1.1.1.1"), viewersCfg, viewsCfg, 1);

        // Inactive for a full window: the next request starts a fresh session.
        clock.advance(Duration.ofSeconds(5));
        watchFullWindow(ALICE, List.of("1.1.1.1"), viewersCfg, viewsCfg, 1);

        assertThat(tracker.getViewCount(ALICE)).isEqualTo(2);
    }

    @Test
    void trackSegmentRequestMultipleIps() {
        watchFullWindow(ALICE, List.of("1.1.1.1", "2.2.2.2", "3.3.3.3"), new Viewers(true, 1), Views.disabled(), 1);

        assertThat(tracker.getViewerCount(ALICE)).isEqualTo(3);
    }

    @Test
    void trackSegmentRequestZeroWindowCountsViewImmediately() {
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", Viewers.disabled(), new Views(true, 0));

        assertThat(tracker.getViewCount(ALICE)).isEqualTo(1);
    }

    @Test
    void trackSegmentRequestZeroWindowCountsEveryIp() {
        var viewsCfg = new Views(true, 0);
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", Viewers.disabled(), viewsCfg);
        tracker.trackSegmentRequest(ALICE, "2.2.2.2", Viewers.disabled(), viewsCfg);
        tracker.trackSegmentRequest(ALICE, "3.3.3.3", Viewers.disabled(), viewsCfg);

        assertThat(tracker.getViewCount(ALICE)).isEqualTo(3);
    }

    @Test
    void trackSegmentRequestViewersDisabledViewsEnabled() {
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", Viewers.disabled(), new Views(true, 0));

        assertThat(tracker.getViewerCount(ALICE)).isZero();
        assertThat(tracker.getViewCount(ALICE)).isEqualTo(1);
    }

    @Test
    void trackSegmentRequestViewersEnabledViewsDisabled() {
        watchFullWindow(ALICE, List.of("1.1.1.1"), new Viewers(true, 1), Views.disabled(), 1);

        assertThat(tracker.getViewerCount(ALICE)).isEqualTo(1);
        assertThat(tracker.getViewCount(ALICE)).isZero();
    }

    // ---- getViewerCount ----

    @Nested
    class GetViewerCount {

        @Test
        void activeViewer() {
            watchFullWindow(ALICE, List.of("1.1.1.1"), new Viewers(true, 5), Views.disabled(), 5);

            assertThat(tracker.getViewerCount(ALICE)).isEqualTo(1);
        }

        @Test
        void expiredViewer() {
            tracker.trackSegmentRequest(ALICE, "1.1.1.1", new Viewers(true, 5), Views.disabled());
            clock.advance(Duration.ofSeconds(10));

            assertThat(tracker.getViewerCount(ALICE)).isZero();
        }

        @Test
        void unknownStream() {
            assertThat(tracker.getViewerCount("nonexistent")).isZero();
        }
    }

    // ---- getViewCount ----

    @Nested
    class GetViewCount {

        @Test
        void inMemory() {
            seedDisk(ALICE, "42");
            tracker.trackSegmentRequest(ALICE, "1.1.1.1", Viewers.disabled(), new Views(true, 5));
            storage.files.clear();

            assertThat(tracker.getViewCount(ALICE)).isEqualTo(42);
        }

        @Test
        void unknownStreamReturnsZero() {
            assertThat(tracker.getViewCount("nonexistent")).isZero();
        }

        @Test
        void fallsBackToDisk() {
            seedDisk("live/unknown", "99");

            assertThat(tracker.getViewCount("live/unknown")).isEqualTo(99);
        }
    }

    // ---- view count persistence ----

    @Test
    void loadViewCountSeedsFromDisk() {
        seedDisk(ALICE, "42");
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", Viewers.disabled(), new Views(true, 5));

        assertThat(tracker.getViewCount(ALICE)).isEqualTo(42);
    }

    @Test
    void loadViewCountIgnoresInvalidData() {
        seedDisk(ALICE, "not-a-number");
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", Viewers.disabled(), new Views(true, 5));

        assertThat(tracker.getViewCount(ALICE)).isZero();
    }

    @Test
    void loadViewCountTrimsWhitespace() {
        seedDisk(ALICE, " 7\n");
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", Viewers.disabled(), new Views(true, 5));

        assertThat(tracker.getViewCount(ALICE)).isEqualTo(7);
    }

    // ---- unregisterStream ----

    @Nested
    class UnregisterStream {

        @Test
        void removesFromMemory() {
            tracker.trackSegmentRequest(ALICE, "1.1.1.1", new Viewers(true, 5), new Views(true, 5));
            tracker.unregisterStream(ALICE);

            assertThat(tracker.getAllStreamStats()).isEmpty();
            assertThat(tracker.getViewerCount(ALICE)).isZero();
        }

        @Test
        void unknownStreamIsSafe() {
            assertThatNoException().isThrownBy(() -> tracker.unregisterStream("nonexistent"));
        }

        @Test
        void persistsViewCount() {
            seedDisk(ALICE, "15");
            tracker.trackSegmentRequest(ALICE, "1.1.1.1", Viewers.disabled(), new Views(true, 5));
            storage.lastWrittenPath = null;
            storage.lastWrittenData = null;

            tracker.unregisterStream(ALICE);

            assertThat(storage.lastWrittenPath).isEqualTo(viewsFile(ALICE));
            assertThat(new String(storage.lastWrittenData, StandardCharsets.UTF_8)).isEqualTo("15");
        }

        @Test
        void doesNotPersistWhenViewsDisabled() {
            tracker.trackSegmentRequest(ALICE, "1.1.1.1", new Viewers(true, 5), Views.disabled());

            tracker.unregisterStream(ALICE);

            assertThat(storage.lastWrittenPath).isNull();
        }
    }

    // ---- cleanup loop ----

    @Test
    void runCleanupOnceFlushesDirtyViewCounts() {
        tracker.trackSegmentRequest(ALICE, "1.1.1.1", Viewers.disabled(), new Views(true, 0));

        tracker.runCleanupOnce();

        assertThat(new String(storage.files.get(viewsFile(ALICE)), StandardCharsets.UTF_8)).isEqualTo("1");

        // Nothing changed since the flush: no second write.
        storage.lastWrittenPath = null;
        tracker.runCleanupOnce();
        assertThat(storage.lastWrittenPath).isNull();
    }

    @Test
    void runCleanupOnceKeepsActiveSessions() {
        var viewersCfg = new Viewers(true, 5);
        watchFullWindow(ALICE, List.of("1.1.1.1"), viewersCfg, Views.disabled(), 5);

        tracker.runCleanupOnce();

        assertThat(tracker.getViewerCount(ALICE)).isEqualTo(1);
    }


    // ---- test doubles ----

    /** In-memory {@link StoragePort}; only the file read/write pair matters for view persistence. */
    private static final class FakeStorage implements StoragePort {
        final Map<Path, byte[]> files = new HashMap<>();
        Path lastWrittenPath;
        byte[] lastWrittenData;

        @Override
        public byte[] readFile(Path path) throws IOException {
            var data = files.get(path);
            if (data == null) {
                throw new NoSuchFileException(path.toString());
            }
            return data;
        }

        @Override
        public void writeFile(Path path, byte[] data) {
            files.put(path, data);
            lastWrittenPath = path;
            lastWrittenData = data;
        }

        @Override
        public void deleteFile(Path path) {
            files.remove(path);
        }

        @Override
        public List<Path> listFiles(String pattern) {
            return List.of();
        }

        @Override
        public long getFileSize(Path path) {
            return 0;
        }

        @Override
        public List<FileMatch> searchFiles(String pattern, List<String> extensions) {
            return List.of();
        }
    }

    /** Manually advanced clock so window semantics can be tested without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
