package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.protocol;

import java.io.IOException;
import java.util.Map;

/**
 * Callbacks fired by {@link RtmpConnection} for one publishing client.
 *
 * <p>Throwing an {@link IOException} from {@code onConnect} or {@code onPublish} refuses the
 * request: the connection answers with an error status and closes. Every method has a no-op
 * default so implementations only override what they care about.
 */
public interface RtmpEventHandler {

    /** A client connected and the connection is about to be served. */
    default void onServe() {}

    /** NetConnection.connect; {@code tcUrl} is what Theatrum matches channel patterns against. */
    default void onConnect(String app, String tcUrl, Map<String, Object> commandObject) throws IOException {}

    default void onCreateStream() throws IOException {}

    /** NetStream.publish; throw to refuse (failed authentication, unknown channel, ...). */
    default void onPublish(String publishingName, String publishingType) throws IOException {}

    /** NetStream.play; playback is out of scope, so implementations throw to refuse. */
    default void onPlay(String streamName) throws IOException {}

    /** {@code @setDataFrame} metadata, with the {@code @setDataFrame} name stripped off. */
    default void onSetDataFrame(long timestamp, byte[] payload) throws IOException {}

    default void onAudio(long timestamp, byte[] payload) throws IOException {}

    default void onVideo(long timestamp, byte[] payload) throws IOException {}

    /** Fired exactly once when the connection ends, whatever the reason. */
    default void onClose() {}
}
