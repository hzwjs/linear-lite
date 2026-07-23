package com.linearlite.server.dto;

public class DigestMailContent {
    private final String subject;
    private final String htmlBody;
    private final String textBody;
    private final int taskCount;

    public DigestMailContent(String subject, String htmlBody, String textBody, int taskCount) {
        this.subject = subject;
        this.htmlBody = htmlBody;
        this.textBody = textBody;
        this.taskCount = taskCount;
    }

    public String getSubject() { return subject; }
    public String getHtmlBody() { return htmlBody; }
    public String getTextBody() { return textBody; }
    public int getTaskCount() { return taskCount; }
}
