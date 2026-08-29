package com.fjourdren.theatrum.infrastructure.adapter.out.metrics;

import com.fjourdren.theatrum.application.port.out.EncodeMetricsPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EncodeMetricsAdapterTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final EncodeMetricsPort adapter = new EncodeMetricsAdapter(new Metrics(registry));

    @Test
    void setEncodeQueueDepth() {
        adapter.setEncodeQueueDepth(5.0);

        assertThat(registry.get("theatrum_encode_queue_depth").gauge().value()).isEqualTo(5.0);
    }

    @Test
    void observeEncodeJobDuration() {
        adapter.observeEncodeJobDuration(1.5);
        adapter.observeEncodeJobDuration(3.0);

        var timer = registry.get("theatrum_encode_job_duration").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(4.5, Offset.offset(0.001));
    }

    @Test
    void incEncodeJobsTotal() {
        adapter.incEncodeJobsTotal("success");
        adapter.incEncodeJobsTotal("failure");
        adapter.incEncodeJobsTotal("success");

        assertThat(registry.get("theatrum_encode_jobs").tags("status", "success").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("theatrum_encode_jobs").tags("status", "failure").counter().count()).isEqualTo(1.0);
    }
}
