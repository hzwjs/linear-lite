package com.linearlite.server.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 项目相对路径；目录以斜线结尾。物理路径与符号链接由宿主检查。 */
public final class ProjectArtifactPathService {
    private ProjectArtifactPathService() { }

    public static List<String> validate(List<String> scopes) {
        if (scopes == null) throw new IllegalArgumentException("产物路径不能为空");
        Set<String> normalized = new HashSet<>();
        for (String scope : scopes) {
            validatePath(scope);
            String key = canonicalKey(scope);
            if (!normalized.add(key)) throw new IllegalArgumentException("产物路径不能重复");
        }
        return List.copyOf(scopes);
    }

    static void validatePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.matches("^[A-Za-z]:.*") || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("产物路径必须是项目相对路径");
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (".".equals(parts[i]) || "..".equals(parts[i]) || (parts[i].isEmpty() && i != parts.length - 1))
                throw new IllegalArgumentException("产物路径必须是规范化的项目相对路径");
        }
    }

    public static String canonicalKey(String scope) {
        return scope.endsWith("/") ? scope.substring(0, scope.length() - 1) : scope;
    }
}
