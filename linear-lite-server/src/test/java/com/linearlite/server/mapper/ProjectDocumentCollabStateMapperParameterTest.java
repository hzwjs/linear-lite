package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.MybatisParameterHandler;
import com.linearlite.server.entity.ProjectDocumentCollabState;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProjectDocumentCollabStateMapperParameterTest {
    @Test
    void bindsBinaryStateWithoutTreatingItAsObjectArray() throws SQLException, IOException {
        Method method = Arrays.stream(ProjectDocumentCollabStateMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals("updateStateIfMatches"))
                .findFirst().orElseThrow();
        ProjectDocumentCollabState state = new ProjectDocumentCollabState();
        state.setStateBinary(new byte[] {1, 2, 3});
        Object stateParameter = method.getParameterTypes()[3] == byte[].class
                ? state.getStateBinary() : state;
        Configuration configuration = new Configuration();
        Object parameters = new ParamNameResolver(configuration, method).getNamedParams(
                new Object[] {736L, "epoch", 0L, stateParameter, null});
        RawSqlSource sql = new RawSqlSource(configuration,
                String.join("\n", method.getAnnotation(Update.class).value()), parameters.getClass());
        MappedStatement statement = new MappedStatement.Builder(configuration, "collaboration-state-binding",
                sql, SqlCommandType.UPDATE).build();

        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        assertDoesNotThrow(() -> new MybatisParameterHandler(statement, parameters, sql.getBoundSql(parameters))
                .setParameters(preparedStatement));
        ArgumentCaptor<InputStream> stream = ArgumentCaptor.forClass(InputStream.class);
        verify(preparedStatement).setBinaryStream(eq(1), stream.capture(), eq(3));
        assertArrayEquals(state.getStateBinary(), stream.getValue().readAllBytes());
    }
}
