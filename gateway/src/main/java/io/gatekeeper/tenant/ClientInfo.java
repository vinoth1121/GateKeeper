package io.gatekeeper.tenant;

public record ClientInfo(String ip, boolean xffUsed) {

    public ClientInfo {
        ip = ip == null ? "" : ip;
    }
}
