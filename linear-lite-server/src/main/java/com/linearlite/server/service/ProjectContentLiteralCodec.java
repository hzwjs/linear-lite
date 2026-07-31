package com.linearlite.server.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.stream.Collectors;

/** 将字面文本编码为可由 Qdrant phrase match 精确定位的 Unicode 字符序列。 */
@Component
public class ProjectContentLiteralCodec {
    public String encode(String value) {
        if (value == null || value.isEmpty()) return "";
        // 每个 Unicode code point 独立成 token，包含空白字符，保持 contains 的连续子串语义。
        return value.toLowerCase(Locale.ROOT).codePoints()
                .mapToObj(codePoint -> "u" + Integer.toHexString(codePoint))
                .collect(Collectors.joining(" "));
    }
}
