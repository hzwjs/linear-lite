package com.linearlite.server.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 项目路径范围的 JSON 数组映射。 */
public class StringListJsonTypeHandler extends BaseTypeHandler<List<String>> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, List<String> value,
                                    JdbcType jdbcType) throws SQLException {
        if (value.stream().anyMatch(item -> item == null)) {
            throw new SQLException("路径范围必须为字符串数组");
        }
        try {
            statement.setString(index, MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new SQLException("路径范围序列化失败", e);
        }
    }

    @Override
    public List<String> getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return read(statement.getString(columnIndex));
    }

    private List<String> read(String json) throws SQLException {
        if (json == null) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isArray()) throw new SQLException("路径范围必须为字符串数组");
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isTextual()) throw new SQLException("路径范围必须为字符串数组");
                values.add(item.textValue());
            }
            return values;
        } catch (JsonProcessingException e) {
            throw new SQLException("路径范围 JSON 无效", e);
        }
    }
}
