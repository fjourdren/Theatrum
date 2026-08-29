package com.fjourdren.theatrum.infrastructure.adapter.in.rtmp.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionInfoTest {

    private static ConnectionInfo withVars(Map<String, String> vars) {
        return new ConnectionInfo("app", "rtmp://host/app", null, vars);
    }

    static List<Arguments> getVarCases() {
        return List.of(
                Arguments.of("found", Map.of("username", "alice"), "username", Optional.of("alice")),
                Arguments.of("not found", Map.of("username", "alice"), "room_id", Optional.empty()),
                Arguments.of("null vars", null, "username", Optional.empty()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("getVarCases")
    void getVar(String name, Map<String, String> vars, String key, Optional<String> expected) {
        assertThat(withVars(vars).getVar(key)).isEqualTo(expected);
    }

    @Test
    void getVarsReturnsACopy() {
        Map<String, String> original = new HashMap<>(Map.of("a", "1", "b", "2"));
        ConnectionInfo info = withVars(original);

        Map<String, String> copy = info.getVars();

        assertThat(copy).hasSize(2);
        assertThatThrownBy(() -> copy.put("c", "3")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(info.vars()).hasSize(2);
    }

    @Test
    void getVarsReturnsEmptyMapWhenVarsAreNull() {
        assertThat(withVars(null).getVars()).isNotNull().isEmpty();
    }

    @Test
    void getUsername() {
        assertThat(withVars(Map.of("username", "alice")).getUsername()).contains("alice");
        assertThat(withVars(Map.of()).getUsername()).isEmpty();
    }

    @Test
    void getHost() {
        assertThat(withVars(Map.of("host", "example.com")).getHost()).contains("example.com");
        assertThat(withVars(Map.of()).getHost()).isEmpty();
    }

    @Test
    void getAppName() {
        assertThat(withVars(Map.of("app", "myapp")).getAppName()).contains("myapp");
        assertThat(withVars(Map.of()).getAppName()).isEmpty();
    }
}
