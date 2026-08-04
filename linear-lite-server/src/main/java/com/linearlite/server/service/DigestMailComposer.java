package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class DigestMailComposer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "backlog", "待规划",
            "todo", "待处理",
            "in_progress", "进行中",
            "in_review", "待审核",
            "done", "已完成",
            "canceled", "已取消",
            "duplicate", "重复任务");

    private final String publicBaseUrl;

    public DigestMailComposer(@Value("${app.public-base-url:}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank() ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public DigestMailContent compose(
            String recipientName,
            LocalDate businessDate,
            LocalDateTime completedWindowStart,
            LocalDateTime completedWindowEnd,
            List<DailySummaryTaskDto> tasks) {
        String businessDay = businessDate.format(DATE_FMT);
        String subject = "【今日汇总】" + businessDay;

        List<DailySummaryTaskDto> overdue = tasks.stream().filter(t -> Boolean.TRUE.equals(t.getOverdue())).toList();
        List<DailySummaryTaskDto> completedToday = tasks.stream()
                .filter(t -> "done".equalsIgnoreCase(t.getStatus())
                        && t.getCompletedAt() != null
                        && !t.getCompletedAt().isBefore(completedWindowStart)
                        && t.getCompletedAt().isBefore(completedWindowEnd))
                .toList();
        List<DailySummaryTaskDto> dueToday = tasks.stream()
                .filter(t -> !Boolean.TRUE.equals(t.getOverdue()) && !completedToday.contains(t))
                .toList();

        String html = buildHtml(recipientName, businessDay, dueToday, overdue, completedToday);
        String text = buildText(recipientName, businessDay, dueToday, overdue, completedToday);

        return new DigestMailContent(subject, html, text, tasks.size());
    }

    private String buildHtml(String recipientName, String businessDay,
                             List<DailySummaryTaskDto> dueToday, List<DailySummaryTaskDto> overdue,
                             List<DailySummaryTaskDto> completedToday) {
        int total = dueToday.size() + overdue.size() + completedToday.size();
        String rootUrl = buildRootUrl();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>");
        sb.append("<body style=\"margin:0;padding:0;background:#f4f7fb;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#0f172a;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f4f7fb;padding:24px 0;\">");
        sb.append("<tr><td align=\"center\"><table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 10px 30px rgba(15,23,42,0.06);\">");
        sb.append("<tr><td style=\"padding:18px 32px;background:linear-gradient(135deg,#eff6ff 0%,#ecfeff 100%);border-bottom:1px solid #dbeafe;\"><span style=\"font-size:18px;font-weight:700;color:#0369a1;\">Linear Lite</span></td></tr>");
        sb.append("<tr><td style=\"padding:28px 32px 8px 32px;\">");
        sb.append("<p style=\"margin:0 0 6px 0;font-size:15px;color:#64748b;\">Hi ").append(escape(nullSafe(recipientName))).append("</p>");
        sb.append("<h1 style=\"margin:0 0 12px 0;font-size:24px;font-weight:700;color:#0f172a;\">今日汇总</h1>");
        sb.append("<p style=\"margin:0;font-size:13px;color:#64748b;\">聚焦今天需要处理的事项，保持推进节奏。</p>");
        sb.append("</td></tr>");

        sb.append("<tr><td style=\"padding:12px 32px 0 32px;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;\"><tr>");
        sb.append(summaryCell("日期", businessDay, "#0f172a"));
        sb.append(summaryCell("总任务", String.valueOf(total), "#0f172a"));
        sb.append(summaryCell("已逾期", String.valueOf(overdue.size()), "#dc2626"));
        sb.append(summaryCell("今日到期", String.valueOf(dueToday.size()), "#0284c7"));
        sb.append(summaryCell("今日完成", String.valueOf(completedToday.size()), "#16a34a"));
        sb.append("</tr></table>");
        sb.append("</td></tr>");

        if (!overdue.isEmpty()) {
            sb.append("<tr><td style=\"padding:20px 32px 0 32px;\"><h2 style=\"margin:0 0 10px 0;font-size:15px;font-weight:700;color:#dc2626;\">已逾期 · ").append(overdue.size()).append("</h2>");
            sb.append(buildTaskRowsHtml(overdue));
            sb.append("</td></tr>");
        }
        if (!dueToday.isEmpty()) {
            sb.append("<tr><td style=\"padding:20px 32px 0 32px;\"><h2 style=\"margin:0 0 10px 0;font-size:15px;font-weight:700;color:#0f172a;\">今日到期 · ").append(dueToday.size()).append("</h2>");
            sb.append(buildTaskRowsHtml(dueToday));
            sb.append("</td></tr>");
        }
        if (!completedToday.isEmpty()) {
            sb.append("<tr><td style=\"padding:20px 32px 0 32px;\"><h2 style=\"margin:0 0 10px 0;font-size:15px;font-weight:700;color:#16a34a;\">今日完成 · ").append(completedToday.size()).append("</h2>");
            sb.append(buildTaskRowsHtml(completedToday));
            sb.append("</td></tr>");
        }

        sb.append("<tr><td style=\"padding:24px 32px;\">");
        if (!rootUrl.isBlank()) {
            sb.append("<a href=\"").append(rootUrl).append("\" style=\"display:inline-block;padding:11px 18px;background:#2563eb;color:#ffffff;text-decoration:none;border-radius:6px;font-size:14px;font-weight:600;\">打开 Linear Lite</a>");
        } else {
            sb.append("<span style=\"display:inline-block;padding:11px 18px;background:#e2e8f0;color:#64748b;border-radius:6px;font-size:14px;font-weight:600;\">请联系管理员配置访问地址</span>");
        }
        sb.append("</td></tr>");
        sb.append("<tr><td style=\"padding:16px 32px 28px 32px;border-top:1px solid #eef2f7;\"><p style=\"margin:0;font-size:12px;color:#94a3b8;\">这封邮件由 Linear Lite 自动发送。如需关闭，请在项目设置中关闭「今日汇总」。</p></td></tr>");
        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    private String summaryCell(String label, String value, String valueColor) {
        return "<td style=\"padding:14px 12px;text-align:left;width:25%;\">"
                + "<span style=\"display:block;font-size:11px;color:#64748b;\">" + escape(label) + "</span>"
                + "<span style=\"display:block;margin-top:4px;font-size:17px;font-weight:700;color:" + valueColor + ";\">" + escape(value) + "</span>"
                + "</td>";
    }

    private String buildTaskRowsHtml(List<DailySummaryTaskDto> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-top:1px solid #e2e8f0;\">");
        for (DailySummaryTaskDto task : tasks) {
            String url = buildTaskUrl(task);
            sb.append("<tr><td style=\"padding:14px 0;border-bottom:1px solid #e2e8f0;\">");
            if (!url.isBlank()) {
                sb.append("<a href=\"").append(url).append("\" style=\"font-size:14px;font-weight:700;color:#2563eb;text-decoration:none;\">")
                        .append(escape(task.getTaskKey())).append("</a>");
            } else {
                sb.append("<span style=\"font-size:14px;font-weight:700;color:#2563eb;\">")
                        .append(escape(task.getTaskKey())).append("</span>");
            }
            sb.append("<span style=\"font-size:15px;color:#0f172a;margin-left:8px;\">")
                    .append(escape(task.getTitle())).append("</span>");
            sb.append("<span style=\"display:block;font-size:12px;line-height:1.6;color:#64748b;margin-top:4px;\">截止 ")
                    .append(formatDate(task.getDueDate()))
                    .append(" · 项目 ")
                    .append(escape(task.getProjectName()))
                    .append(" · 状态 ")
                    .append(escape(statusLabel(task.getStatus())))
                    .append(" · 进度 ")
                    .append(escape(progressLabel(task.getProgressPercent())))
                    .append("</span>");
            sb.append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildText(String recipientName, String businessDay,
                             List<DailySummaryTaskDto> dueToday, List<DailySummaryTaskDto> overdue,
                             List<DailySummaryTaskDto> completedToday) {
        int total = dueToday.size() + overdue.size() + completedToday.size();
        String rootUrl = buildRootUrl();
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(nullSafe(recipientName)).append("\n\n");
        sb.append("今日汇总\n");
        sb.append(businessDay).append(" · 共 ").append(total).append(" 个任务");
        sb.append(" · 已逾期 ").append(overdue.size());
        sb.append(" · 今日到期 ").append(dueToday.size());
        sb.append(" · 今日完成 ").append(completedToday.size()).append("\n\n");
        if (!overdue.isEmpty()) {
            sb.append("已逾期 · ").append(overdue.size()).append("\n");
            appendTaskText(sb, overdue);
            sb.append("\n");
        }
        if (!dueToday.isEmpty()) {
            sb.append("今日到期 · ").append(dueToday.size()).append("\n");
            appendTaskText(sb, dueToday);
            sb.append("\n");
        }
        if (!completedToday.isEmpty()) {
            sb.append("今日完成 · ").append(completedToday.size()).append("\n");
            appendTaskText(sb, completedToday);
            sb.append("\n");
        }
        sb.append("打开 Linear Lite：")
                .append(rootUrl.isBlank() ? "请联系管理员配置访问地址" : rootUrl)
                .append("\n\n");
        sb.append("这封邮件由 Linear Lite 自动发送。如需关闭，请在项目设置中关闭「今日汇总」。\n");
        return sb.toString();
    }

    private void appendTaskText(StringBuilder sb, List<DailySummaryTaskDto> tasks) {
        for (DailySummaryTaskDto task : tasks) {
            String url = buildTaskUrl(task);
            sb.append("- [").append(nullSafe(task.getTaskKey())).append("] ")
                    .append(nullSafe(task.getTitle()))
                    .append("（截止 ").append(formatDate(task.getDueDate()))
                    .append(" · 项目 ").append(nullSafe(task.getProjectName()))
                    .append(" · 状态 ").append(statusLabel(task.getStatus()))
                    .append(" · 进度 ").append(progressLabel(task.getProgressPercent()))
                    .append("）\n");
            if (!url.isBlank()) {
                sb.append(url).append("\n");
            }
        }
    }

    private String buildRootUrl() {
        return publicBaseUrl.isBlank() ? "" : publicBaseUrl + "/";
    }

    private String buildTaskUrl(DailySummaryTaskDto task) {
        if (publicBaseUrl.isBlank()) return "";
        return publicBaseUrl + "/projects/" + task.getProjectId() + "/tasks/" + urlEncode(task.getTaskKey());
    }

    private String statusLabel(String status) {
        if (status == null || status.isBlank()) return "未设置";
        return STATUS_LABELS.getOrDefault(status.toLowerCase(), "未设置");
    }

    private String progressLabel(Integer progressPercent) {
        return progressPercent == null ? "--" : progressPercent + "%";
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
