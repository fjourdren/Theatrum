package com.fjourdren.theatrum.domain.model;

public record Server(int httpPort, int rtmpPort, Rtmp rtmp) {
}
