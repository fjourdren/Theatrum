package com.fjourdren.theatrum.application.port.in.exception;

import lombok.experimental.StandardException;

/** Raised when an RTMP publisher fails the stream-key check for a channel. */
@StandardException
public class AuthenticationException extends RuntimeException {
}
