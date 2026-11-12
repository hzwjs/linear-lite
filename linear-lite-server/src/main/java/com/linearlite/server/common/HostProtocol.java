package com.linearlite.server.common;

/** 宿主 REST 路径、响应标记和批次能力使用同一协议版本。 */
public final class HostProtocol {
    public static final int VERSION = 3;
    public static final String BASE_PATH = "/api/collaboration-host";
    public static final String API_PATH = BASE_PATH + "/v" + VERSION;
    public static final String HEADER = "X-Linear-Lite-Host-Protocol";
    public static final String UPGRADE_MESSAGE = "宿主协作协议已更新，请下载新版 Linear Lite 协作宿主";

    private HostProtocol() { }
}
