package com.linearlite.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;
    /** 发件人地址，必须与 SMTP 登录账号一致（如 163 要求），默认使用 spring.mail.username */
    private final String fromAddress;

    public SmtpEmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.username:}") String smtpUsername,
            @Value("${app.mail.from:}") String appMailFrom
    ) {
        this.mailSender = mailSender;
        this.fromAddress = (appMailFrom != null && !appMailFrom.isBlank())
                ? appMailFrom
                : smtpUsername;
    }

    @Override
    public void sendVerificationCode(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(email);
        message.setSubject("Your Linear Lite verification code");
        message.setText("Your verification code is " + code + ". It expires in 10 minutes.");
        mailSender.send(message);
    }

    @Override
    public void sendProjectInvitation(String email, String projectName) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(email);
        message.setSubject("You've been invited to a Linear Lite project");
        message.setText("You have been invited to join project \"" + projectName + "\". Sign in or register with this email to access it.");
        mailSender.send(message);
    }
}
