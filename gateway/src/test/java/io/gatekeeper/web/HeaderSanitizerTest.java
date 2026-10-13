package io.gatekeeper.web;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class HeaderSanitizerTest {

    @Test
    void recognizesEveryHopByHopHeader() {
        for (String header : new String[] {
                "Connection", "connection", "Keep-Alive", "Transfer-Encoding", "TE",
                "Trailer", "Upgrade", "Proxy-Authenticate", "Proxy-Authorization"}) {
            assertThat(HeaderSanitizer.isHopByHop(header)).as(header).isTrue();
        }
    }

    @Test
    void leavesEndToEndHeadersAlone() {
        for (String header : new String[] {
                "Content-Type", "X-GateKeeper-Decision", "RateLimit-Remaining",
                "X-Admin-Key", "Retry-After", null, ""}) {
            assertThat(HeaderSanitizer.isHopByHop(header)).as(String.valueOf(header)).isFalse();
        }
    }

    @Test
    void setHeaderSafeDropsHopByHopAndKeepsOthers() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        HeaderSanitizer.setHeaderSafe(response, "Connection", "close");
        HeaderSanitizer.setHeaderSafe(response, "X-Custom", "kept");
        assertThat(response.getHeader("Connection")).isNull();
        assertThat(response.getHeader("X-Custom")).isEqualTo("kept");
    }

    @Test
    void addHeaderSafeDropsHopByHopAndKeepsOthers() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        HeaderSanitizer.addHeaderSafe(response, "Transfer-Encoding", "chunked");
        HeaderSanitizer.addHeaderSafe(response, "X-Trace", "kept");
        assertThat(response.getHeader("Transfer-Encoding")).isNull();
        assertThat(response.getHeader("X-Trace")).isEqualTo("kept");
    }

    @Test
    void wrapperStripsHopByHopHeadersOnSetAndAdd() {
        MockHttpServletResponse mock = new MockHttpServletResponse();
        CommitTrackingResponseWrapper wrapper = new CommitTrackingResponseWrapper(mock);
        wrapper.setHeader("Connection", "close");
        wrapper.addHeader("Keep-Alive", "timeout=5");
        wrapper.addIntHeader("TE", 1);
        wrapper.addDateHeader("Upgrade", 123L);
        wrapper.setHeader("X-GateKeeper-Decision", "allowed");
        assertThat(mock.getHeader("Connection")).isNull();
        assertThat(mock.getHeader("Keep-Alive")).isNull();
        assertThat(mock.getHeader("TE")).isNull();
        assertThat(mock.getHeader("Upgrade")).isNull();
        assertThat(mock.getHeader("X-GateKeeper-Decision")).isEqualTo("allowed");
    }

    @Test
    void wrapperTracksFirstWriteTime() throws Exception {
        MockHttpServletResponse mock = new MockHttpServletResponse();
        CommitTrackingResponseWrapper wrapper = new CommitTrackingResponseWrapper(mock);
        assertThat(wrapper.firstWriteNanos()).isZero();
        wrapper.getWriter().write("hello");
        assertThat(wrapper.firstWriteNanos()).isPositive();
    }
}
