package com.linearlite.server.service;

import com.linearlite.server.dto.DigestMailContent;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class DigestMailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;

    public DigestMailSender(
            JavaMailSender mailSender,
            @Value("${app.mail.from:}") String fromAddress,
            @Value("${app.mail.from-name:Linear Lite}") String fromName
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    public void send(String to, DigestMailContent content) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            if (fromAddress != null && !fromAddress.isBlank()) {
                helper.setFrom(fromAddress, fromName);
            }
            helper.setTo(to);
            helper.setSubject(content.getSubject());
            helper.setText(content.getTextBody(), content.getHtmlBody());
            mailSender.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("发送汇总邮件失败: " + to, e);
        }
    }
}
