package com.linearlite.server.mapper;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StringListJsonTypeHandlerTest {
    private final StringListJsonTypeHandler handler = new StringListJsonTypeHandler();

    @Test
    void writesAndReadsStringScopesWithoutChangingPaths() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        List<String> paths = List.of("src/", "docs/方案设计/说明.md");
        handler.setNonNullParameter(statement, 1, paths, null);
        verify(statement).setString(1, "[\"src/\",\"docs/方案设计/说明.md\"]");

        ResultSet result = mock(ResultSet.class);
        when(result.getString("write_scopes")).thenReturn("[\"src/\",\"docs/方案设计/说明.md\"]");
        assertEquals(paths, handler.getNullableResult(result, "write_scopes"));
    }

    @Test
    void rejectsNonStringScopesInsteadOfCoercingThem() throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.getString("write_scopes")).thenReturn("[\"src/\", 5]");
        assertThrows(SQLException.class, () -> handler.getNullableResult(result, "write_scopes"));
        when(result.getString("write_scopes")).thenReturn("{\"path\":\"src/\"}");
        assertThrows(SQLException.class, () -> handler.getNullableResult(result, "write_scopes"));
    }
}
