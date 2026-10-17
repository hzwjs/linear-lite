package com.linearlite.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "collaboration")
public class CollaborationProperties {
    private String internalKey = "";
    private String serverUrl = "http://127.0.0.1:9091";
    private String websocketUrl = "";
    private long blocknoteMaxContentBytes = 10L * 1024 * 1024;
    private long htmlMaxContentBytes = 5L * 1024 * 1024;

    public String getInternalKey() { return internalKey; }
    public void setInternalKey(String internalKey) { this.internalKey = internalKey; }
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public String getWebsocketUrl() { return websocketUrl; }
    public void setWebsocketUrl(String websocketUrl) { this.websocketUrl = websocketUrl; }
    public long getBlocknoteMaxContentBytes() { return blocknoteMaxContentBytes; }
    public void setBlocknoteMaxContentBytes(long value) { this.blocknoteMaxContentBytes = value; }
    public long getHtmlMaxContentBytes() { return htmlMaxContentBytes; }
    public void setHtmlMaxContentBytes(long value) { this.htmlMaxContentBytes = value; }
}
