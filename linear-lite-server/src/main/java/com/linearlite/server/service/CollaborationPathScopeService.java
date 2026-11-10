package com.linearlite.server.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 项目相对路径；目录以斜线结尾。物理路径与符号链接由宿主检查。 */
public final class CollaborationPathScopeService {
    private CollaborationPathScopeService() { }

    public static List<String> validate(List<String> scopes) {
        if (scopes == null) throw new IllegalArgumentException("写入范围不能为空");
        Set<String> normalized = new HashSet<>();
        for (String scope : scopes) {
            if (scope == null || scope.isBlank() || scope.startsWith("/") || scope.contains("\\")
                    || scope.matches("^[A-Za-z]:.*") || scope.indexOf('\0') >= 0)
                throw new IllegalArgumentException("写入范围必须是项目相对路径");
            String[] parts = scope.split("/", -1);
            for (int i = 0; i < parts.length; i++) {
                if (".".equals(parts[i]) || "..".equals(parts[i]) || (parts[i].isEmpty() && i != parts.length - 1))
                    throw new IllegalArgumentException("写入范围必须是规范化的项目相对路径");
            }
            String key = canonicalKey(scope);
            if (!normalized.add(key)) throw new IllegalArgumentException("写入范围不能重复");
        }
        return List.copyOf(scopes);
    }

    public static boolean overlaps(String left, String right) {
        String a = canonicalKey(left);
        String b = canonicalKey(right);
        return a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/");
    }

    public static boolean overlaps(List<String> left, List<String> right) {
        return left.stream().anyMatch(a -> right.stream().anyMatch(b -> overlaps(a, b)));
    }

    public static String canonicalKey(String scope) {
        return scope.endsWith("/") ? scope.substring(0, scope.length() - 1) : scope;
    }
}
