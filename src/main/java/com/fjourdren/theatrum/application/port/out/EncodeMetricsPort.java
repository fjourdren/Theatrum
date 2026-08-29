package com.fjourdren.theatrum.application.port.out;

public interface EncodeMetricsPort {

    void setEncodeQueueDepth(double depth);

    void observeEncodeJobDuration(double seconds);

    void incEncodeJobsTotal(String status);
}
