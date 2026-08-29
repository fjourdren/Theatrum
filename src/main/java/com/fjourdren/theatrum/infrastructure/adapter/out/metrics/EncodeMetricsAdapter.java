package com.fjourdren.theatrum.infrastructure.adapter.out.metrics;

import com.fjourdren.theatrum.application.port.out.EncodeMetricsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EncodeMetricsAdapter implements EncodeMetricsPort {

    private final Metrics metrics;

    @Override
    public void setEncodeQueueDepth(double depth) {
        metrics.setEncodeQueueDepth(depth);
    }

    @Override
    public void observeEncodeJobDuration(double seconds) {
        metrics.observeEncodeJobDuration(seconds);
    }

    @Override
    public void incEncodeJobsTotal(String status) {
        metrics.incEncodeJobs(status);
    }
}
