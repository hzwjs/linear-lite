package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.Project;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class DigestMailComposer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String publicBaseUrl;

    public DigestMailComposer(@Value("${app.public-base-url:}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank() ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public DigestMailContent compose(Project project, String recipientName, List<DailySummaryTaskDto> tasks) {
        String today = LocalDateTime.now().format(DATE_FMT);
        String subject = "【今日汇总】" + project.getName() + " · " + today;

        List<DailySummaryTaskDto> overdue = tasks.stream().filter(t -> Boolean.TRUE.equals(t.getOverdue())).toList();
        List<DailySummaryTaskDto> dueToday = tasks.stream().filter(t -> !Boolean.TRUE.equals(t.getOverdue())).toList();

        String html = buildHtml(project, recipientName, today, dueToday, overdue);
        String text = buildText(project, recipientName, today, dueToday, overdue);

        return new DigestMailContent(subject, html, text, tasks.size());
    }

    private String buildHtml(Project project, String recipientName, String today,
                             List<DailySummaryTaskDto> dueToday, List<DailySummaryTaskDto> overdue) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"margin:0;padding:0;background:#f6f7f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#1f2937;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f6f7f9;padding:24px 0;\">");
        sb.append("<tr><td align=\"center\"><table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,0.08);\">");
        sb.append("<tr><td style=\"background:#5e6ad2;padding:20px 32px;\"><span style=\"font-size:18px;font-weight:600;color:#ffffff;letter-spacing:0.4px;\">Linear Lite</span></td></tr>");
        sb.append("<tr><td style=\"padding:28px 32px 8px 32px;\">");
        sb.append("<p style=\"margin:0 0 4px 0;font-size:15px;color:#6b7280;\">").append(escape("Hi " + nullSafe(recipientName))).append("</p>");
        sb.append("<h1 style=\"margin:0 0 12px 0;font-size:22px;font-weight:600;color:#111827;\">").append(escape(project.getName())).append(" · 今日汇总</h1>");
        sb.append("<p style=\"margin:0;font-size:13px;color:#9ca3af;\">").append(today).append(" · 共 ").append(dueToday.size() + overdue.size()).append(" 个待处理任务</p>");
        sb.append("</td></tr>");

        if (!overdue.isEmpty()) {
            sb.append("<tr><td style=\"padding:8px 32px 0 32px;\"><h2 style=\"margin:0 0 8px 0;font-size:14px;color:#dc2626;\">已逾期 · ").append(overdue.size()).append("</h2>");
            sb.append(buildTaskRowsHtml(overdue));
            sb.append("</td></tr>");
        }
        if (!dueToday.isEmpty()) {
            sb.append("<tr><td style=\"padding:16px 32px 0 32px;\"><h2 style=\"margin:0 0 8px 0;font-size:14px;color:#111827;\">今日到期 · ").append(dueToday.size()).append("</h2>");
            sb.append(buildTaskRowsHtml(dueToday));
            sb.append("</td></tr>");
        }

        sb.append("<tr><td style=\"padding:24px 32px;\"><a href=\"").append(publicBaseUrl).append("/\" style=\"display:inline-block;padding:10px 20px;background:#5e6ad2;color:#ffffff;text-decoration:none;border-radius:6px;font-size:14px;font-weight:500;\">打开 Linear Lite</a></td></tr>");
        sb.append("<tr><td style=\"padding:16px 32px 28px 32px;border-top:1px solid #f0f0f0;\"><p style=\"margin:0;font-size:12px;color:#9ca3af;\">这封邮件由 Linear Lite 自动发送。如需关闭，请在项目设置中关闭「今日汇总」。</p></td></tr>");
        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    private String buildTaskRowsHtml(List<DailySummaryTaskDto> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">");
        for (DailySummaryTaskDto task : tasks) {
            String url = publicBaseUrl + "/projects/" + task.getProjectId() + "/tasks/" + urlEncode(task.getTaskKey());
            sb.append("<tr><td style=\"padding:10px 0;border-bottom:1px solid #f3f4f6;\">");
            sb.append("<a href=\"").append(url).append("\" style=\"font-size:14px;font-weight:500;color:#5e6ad2;text-decoration:none;\">").append(escape(task.getTaskKey())).append("</a>");
            sb.append("<span style=\"font-size:14px;color:#374151;margin-left:8px;\">").append(escape(task.getTitle())).append("</span>");
            sb.append("<span style=\"display:block;font-size:12px;color:#9ca3af;margin-top:2px;\">截止 ").append(formatDate(task.getDueDate())).append(" · 状态 ").append(escape(nullSafe(task.getStatus()))).append("</span>");
            sb.append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildText(Project project, String recipientName, String today,
                             List<DailySummaryTaskDto> dueToday, List<DailySummaryTaskDto> overdue) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(nullSafe(recipientName)).append("\n\n");
        sb.append(project.getName()).append(" · 今日汇总\n").append(today).append(" · 共 ")
          .append(dueToday.size() + overdue.size()).append(" 个待处理任务\n\n");
        if (!overdue.isEmpty()) {
            sb.append("已逾期 · ").append(overdue.size()).append("\n");
            for (DailySummaryTaskDto task : overdue) {
                sb.append("- [").append(task.getTaskKey()).append("] ").append(task.getTitle())
                  .append("（截止 ").append(formatDate(task.getDueDate())).append("）\n")
                  .append(publicBaseUrl).append("/projects/").append(task.getProjectId())
                  .append("/tasks/").append(task.getTaskKey()).append("\n");
            }
            sb.append("\n");
        }
        if (!dueToday.isEmpty()) {
            sb.append("今日到期 · ").append(dueToday.size()).append("\n");
            for (DailySummaryTaskDto task : dueToday) {
                sb.append("- [").append(task.getTaskKey()).append("] ").append(task.getTitle())
                  .append("（截止 ").append(formatDate(task.getDueDate())).append("）\n")
                  .append(publicBaseUrl).append("/projects/").append(task.getProjectId())
                  .append("/tasks/").append(task.getTaskKey()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("打开 Linear Lite：").append(publicBaseUrl).append("/\n\n");
        sb.append("这封邮件由 Linear Lite 自动发送。如需关闭，请在项目设置中关闭「今日汇总」。\n");
        return sb.toString();
    }

    private String formatDate(LocalDateTime date) {
        return date == null ? "—" : date.format(DATETIME_FMT);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String urlEncode(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
