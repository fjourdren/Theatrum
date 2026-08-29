package com.fjourdren.theatrum.domain.model;

import com.fjourdren.theatrum.domain.constant.VideoConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StreamTest {

    @Test
    void restreamTypeValue() {
        assertThat(StreamType.RESTREAM.value()).isEqualTo("restream");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "simple path,               videos/{username}",
            "nested path,               live/{room_id}/{user}",
            "path with builtin function,recordings/{%STARTING_DATE%}"
    })
    void getMasterPlaylistTemplatePath(String name, String path) {
        Stream stream = Stream.builder().path(path).build();
        assertThat(stream.getMasterPlaylistTemplatePath()).isEqualTo(path + "/" + VideoConstants.MASTER_PLAYLIST);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "simple path,               videos/{username}",
            "nested path,               live/{room_id}/{user}",
            "path with builtin function,recordings/{%STARTING_DATE%}"
    })
    void getDashManifestTemplatePath(String name, String path) {
        Stream stream = Stream.builder().path(path).build();
        assertThat(stream.getDashManifestTemplatePath()).isEqualTo(path + "/" + VideoConstants.DASH_MANIFEST);
    }
}
