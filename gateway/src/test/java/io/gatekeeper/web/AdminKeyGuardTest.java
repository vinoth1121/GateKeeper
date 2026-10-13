package io.gatekeeper.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminKeyGuardTest {

    private final AdminKeyGuard guard = new AdminKeyGuard("test-admin-key");

    @Test
    void acceptsExactKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AdminKeyGuard.ADMIN_KEY_HEADER, "test-admin-key");
        assertThat(guard.isValid(request)).isTrue();
    }

    @Test
    void rejectsWrongKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AdminKeyGuard.ADMIN_KEY_HEADER, "wrong");
        assertThat(guard.isValid(request)).isFalse();
    }

    @Test
    void rejectsMissingHeader() {
        assertThat(guard.isValid(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void rejectsBlankHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AdminKeyGuard.ADMIN_KEY_HEADER, "   ");
        assertThat(guard.isValid(request)).isFalse();
    }

    @Test
    void nullRequestIsRejected() {
        HttpServletRequest request = null;
        assertThat(guard.isValid(request)).isFalse();
    }

    @Test
    void blankConfiguredKeyFallsBackToDevelopmentDefault() {
        AdminKeyGuard defaultGuard = new AdminKeyGuard(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AdminKeyGuard.ADMIN_KEY_HEADER, AdminKeyGuard.DEFAULT_ADMIN_KEY);
        assertThat(defaultGuard.isValid(request)).isTrue();
    }
}
