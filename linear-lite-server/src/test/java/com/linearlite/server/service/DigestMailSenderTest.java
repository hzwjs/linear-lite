package com.linearlite.server.service;

import com.linearlite.server.dto.DigestMailContent;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DigestMailSenderTest {

    private JavaMailSender mailSender;
    private DigestMailSender sender;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        sender = new DigestMailSender(mailSender, "noreply@example.com", "Linear Lite");
    }

    @Test
    void sendInvokesMailSenderWithMimeMessage() {
        DigestMailContent content = new DigestMailContent("主题", "<html></html>", "text", 1);

        sender.send("user@example.com", content);

        verify(mailSender).send(any(MimeMessage.class));
    }
}
