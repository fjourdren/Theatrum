package com.fjourdren.theatrum.domain.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveStreamRegistryTest {

    private final LiveStreamRegistry registry = new LiveStreamRegistry();

    @Test
    void getOrRegisterStoresOnFirstPublish() {
        Map<String, String> vars = Map.of("UUID", "abc");

        assertThat(registry.getOrRegister("live/alice", vars)).isEqualTo(vars);
        assertThat(registry.getBuiltinVars("live/alice")).contains(vars);
    }

    @Test
    void getOrRegisterKeepsExistingVarsOnReconnection() {
        registry.getOrRegister("live/alice", Map.of("UUID", "first"));

        Map<String, String> returned = registry.getOrRegister("live/alice", Map.of("UUID", "second"));

        assertThat(returned).containsEntry("UUID", "first");
        assertThat(registry.getBuiltinVars("live/alice")).contains(Map.of("UUID", "first"));
    }

    @Test
    void getBuiltinVarsIsEmptyForUnknownKey() {
        assertThat(registry.getBuiltinVars("live/nobody")).isEmpty();
    }

    @Test
    void unregisterRemovesTheEntry() {
        registry.getOrRegister("live/alice", Map.of("UUID", "abc"));

        registry.unregister("live/alice");

        assertThat(registry.getBuiltinVars("live/alice")).isEmpty();
    }

    @Test
    void unregisterIsSafeForUnknownKey() {
        registry.unregister("live/nobody");
    }

    @Test
    void reRegistrationAfterUnregisterUsesTheNewVars() {
        registry.getOrRegister("key1", Map.of("UUID", "first-uuid"));
        registry.unregister("key1");

        assertThat(registry.getOrRegister("key1", Map.of("UUID", "second-uuid")))
                .containsEntry("UUID", "second-uuid");
    }

    @Test
    void keysAreIndependent() {
        registry.getOrRegister("key1", Map.of("UUID", "uuid-1"));
        registry.getOrRegister("key2", Map.of("UUID", "uuid-2"));

        assertThat(registry.getBuiltinVars("key1")).contains(Map.of("UUID", "uuid-1"));
        assertThat(registry.getBuiltinVars("key2")).contains(Map.of("UUID", "uuid-2"));

        registry.unregister("key1");

        assertThat(registry.getBuiltinVars("key1")).isEmpty();
        assertThat(registry.getBuiltinVars("key2")).contains(Map.of("UUID", "uuid-2"));
    }

    @Test
    void concurrentGetOrRegisterReturnsOneConsistentValue() throws InterruptedException {
        int threads = 100;
        var barrier = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(threads);
        var results = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();

        for (int i = 0; i < threads; i++) {
            int id = i;
            Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    results.add(registry.getOrRegister("key1", Map.of("UUID", "uuid-" + id)).get("UUID"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        barrier.countDown();
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(results).hasSize(1);
    }
}
