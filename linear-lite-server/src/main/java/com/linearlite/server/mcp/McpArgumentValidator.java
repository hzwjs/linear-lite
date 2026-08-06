package com.linearlite.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.linearlite.server.dto.TaskLabelItemRequest;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * MCP 工具参数的唯一入口校验器。
 *
 * <p>工具参数先经过 JSON 结构校验，再交给领域服务执行，避免把 JSON 节点直接泄露到业务层。</p>
 */
final class McpArgumentValidator {

    private McpArgumentValidator() {
    }

    static JsonNode object(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new McpInvalidParamsException("arguments 必须是 JSON 对象");
        }
        return arguments;
    }

    static void fields(JsonNode arguments, String... allowed) {
        Set<String> allowedNames = Set.of(allowed);
        Iterator<String> names = arguments.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowedNames.contains(name)) {
                throw new McpInvalidParamsException("不支持的参数: " + name);
            }
        }
    }

    static String requiredText(JsonNode object, String name, int maxLength) {
        JsonNode value = object.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new McpInvalidParamsException(name + " 必须是非空字符串");
        }
        String text = value.textValue().trim();
        if (text.length() > maxLength) {
            throw new McpInvalidParamsException(name + " 不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    static String optionalText(JsonNode object, String name, int maxLength) {
        JsonNode value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw new McpInvalidParamsException(name + " 必须是字符串");
        }
        String text = value.textValue().trim();
        if (text.length() > maxLength) {
            throw new McpInvalidParamsException(name + " 不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    static Long optionalLong(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new McpInvalidParamsException(name + " 必须是整数");
        }
        return value.longValue();
    }

    static Integer optionalInteger(JsonNode object, String name, int min, int max) {
        JsonNode value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new McpInvalidParamsException(name + " 必须是整数");
        }
        int number = value.intValue();
        if (number < min || number > max) {
            throw new McpInvalidParamsException(name + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return number;
    }

    static Boolean optionalBoolean(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new McpInvalidParamsException(name + " 必须是布尔值");
        }
        return value.booleanValue();
    }

    static LocalDateTime optionalDateTime(JsonNode object, String name) {
        String value = optionalText(object, name, 64);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new McpInvalidParamsException(name + " 必须是 ISO-8601 LocalDateTime");
        }
    }

    static List<Long> optionalLongList(JsonNode object, String name, int maxItems) {
        JsonNode value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isArray() || value.size() > maxItems) {
            throw new McpInvalidParamsException(name + " 必须是最多 " + maxItems + " 项的数组");
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isIntegralNumber() || !item.canConvertToLong()) {
                throw new McpInvalidParamsException(name + " 数组元素必须是整数");
            }
            result.add(item.longValue());
        }
        return result;
    }

    static List<TaskLabelItemRequest> optionalLabels(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isArray() || value.size() > 100) {
            throw new McpInvalidParamsException(name + " 必须是最多 100 项的数组");
        }
        List<TaskLabelItemRequest> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new McpInvalidParamsException(name + " 的元素必须是对象");
            }
            fields(item, "id", "name");
            Long id = optionalLong(item, "id");
            String labelName = optionalText(item, "name", 64);
            if ((id == null) == (labelName == null || labelName.isBlank())) {
                throw new McpInvalidParamsException(name + " 的每个元素必须且只能包含 id 或 name");
            }
            TaskLabelItemRequest label = new TaskLabelItemRequest();
            label.setId(id);
            label.setName(labelName);
            result.add(label);
        }
        return result;
    }
}
