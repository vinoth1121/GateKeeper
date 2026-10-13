package io.gatekeeper.web;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Response wrapper used by the GateKeeperFilter around the proxied call.
 *
 * Two jobs:
 * 1. Guard against hop-by-hop headers leaking into responses: every header
 *    mutation is routed through HeaderSanitizer, which drops Connection,
 *    Keep-Alive, Transfer-Encoding, TE, Trailer, Upgrade and Proxy-*.
 *    Spring Cloud Gateway MVC already strips most of these on proxied
 *    responses; this wrapper is the belt-and-braces guard for it and for
 *    the gateway's own JSON error responses.
 * 2. Capture approximately when the response was first committed (first
 *    body byte written or explicit flush), used for honest overhead
 *    accounting in the filter.
 */
public class CommitTrackingResponseWrapper extends HttpServletResponseWrapper {

    private volatile long firstWriteNanos = 0L;

    public CommitTrackingResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    /** NanoTime of the first body write/flush, or 0 when nothing was written. */
    public long firstWriteNanos() {
        return firstWriteNanos;
    }

    private void markWrite() {
        if (firstWriteNanos == 0L) {
            firstWriteNanos = System.nanoTime();
        }
    }

    @Override
    public void setHeader(String name, String value) {
        HeaderSanitizer.setHeaderSafe((HttpServletResponse) getResponse(), name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        HeaderSanitizer.addHeaderSafe((HttpServletResponse) getResponse(), name, value);
    }

    @Override
    public void addIntHeader(String name, int value) {
        if (!HeaderSanitizer.isHopByHop(name)) {
            super.addIntHeader(name, value);
        }
    }

    @Override
    public void addDateHeader(String name, long date) {
        if (!HeaderSanitizer.isHopByHop(name)) {
            super.addDateHeader(name, date);
        }
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        markWrite();
        return super.getOutputStream();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        markWrite();
        return super.getWriter();
    }

    @Override
    public void flushBuffer() throws IOException {
        markWrite();
        super.flushBuffer();
    }
}
