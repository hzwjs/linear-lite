package com.linearlite.server.dto;

/**
 * 登录请求：邮箱或用户名、密码。
 */
public class LoginRequest {

    private String identity;
    private String password;

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
