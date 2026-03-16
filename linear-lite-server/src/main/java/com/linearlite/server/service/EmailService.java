package com.linearlite.server.service;

public interface EmailService {

    void sendVerificationCode(String email, String code);
}
