package com.linearlite.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 远程终端团队版运行参数；caKek 缺失时整个功能不可用，不降级为明文存储。 */
@Component
@ConfigurationProperties(prefix = "remote-terminal")
public class RemoteTerminalProperties {

    private String caKek = "";
    private int certificateRatePerMinute = 30;

    public String getCaKek() {
        return caKek;
    }

    public void setCaKek(String caKek) {
        this.caKek = caKek;
    }

    public int getCertificateRatePerMinute() {
        return certificateRatePerMinute;
    }

    public void setCertificateRatePerMinute(int certificateRatePerMinute) {
        this.certificateRatePerMinute = certificateRatePerMinute;
    }
}
