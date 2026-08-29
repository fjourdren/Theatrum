package com.fjourdren.theatrum.infrastructure.adapter.in.web.handlers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "X-Forwarded-For single      | 1.2.3.4                      | 5.6.7.8:1234    | 1.2.3.4",
            "X-Forwarded-For multiple    | 1.2.3.4, 10.0.0.1, 10.0.0.2  | 5.6.7.8:1234    | 1.2.3.4",
            "X-Forwarded-For with spaces | '  1.2.3.4  '                | 5.6.7.8:1234    | 1.2.3.4",
            "RemoteAddr with port        | ''                           | 192.168.1.1:5000| 192.168.1.1",
            "RemoteAddr without port     | ''                           | 192.168.1.1     | 192.168.1.1",
            "IPv6 RemoteAddr             | ''                           | [::1]:5000      | ::1",
            "IPv6 without port           | ''                           | ::1             | ::1"
    })
    void getClientIp(String name, String xForwardedFor, String remoteAddr, String expected) {
        assertThat(ClientIp.from(xForwardedFor, remoteAddr)).isEqualTo(expected);
    }
}
