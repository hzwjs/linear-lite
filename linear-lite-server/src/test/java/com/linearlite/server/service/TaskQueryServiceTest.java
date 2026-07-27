package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.TaskListItemResponse;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.LabelMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskLabelMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskQueryServiceTest {

    @Test
    void listItemsOmitsDescriptionAndComputesCountsFromLoadedProjectTasks() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Task.class);
        TaskListItemResponse parent = task(1L, "ENG-1", null, "todo");
        TaskListItemResponse childDone = task(2L, "ENG-2", 1L, "done");
        TaskListItemResponse childTodo = task(3L, "ENG-3", 1L, "todo");
        AtomicInteger taskListItemCalls = new AtomicInteger();
        AtomicInteger favoriteTaskIdCalls = new AtomicInteger();
        AtomicInteger projectLabelCalls = new AtomicInteger();

        TaskMapper taskMapper = proxy(TaskMapper.class, invocation -> {
            String name = invocation.getMethod().getName();
            if ("selectListItems".equals(name)) {
                throw new AssertionError("task list should map SQL directly to TaskListItemResponse");
            }
            if ("selectListItemResponses".equals(name)) {
                taskListItemCalls.incrementAndGet();
                return List.of(parent, childDone, childTodo);
            }
            if ("selectList".equals(name)) {
                throw new AssertionError("task list items should use the dedicated lightweight list query");
            }
            if ("selectSubIssueCounts".equals(name)) {
                throw new AssertionError("topLevelOnly=false should compute sub-issue counts from loaded project tasks");
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        ProjectMemberMapper projectMemberMapper = proxy(ProjectMemberMapper.class, invocation -> {
            if ("selectCount".equals(invocation.getMethod().getName())) {
                return 1L;
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        TaskFavoriteMapper taskFavoriteMapper = proxy(TaskFavoriteMapper.class, invocation -> {
            if ("selectFavoriteTaskIdsByUser".equals(invocation.getMethod().getName())) {
                favoriteTaskIdCalls.incrementAndGet();
                return Collections.emptyList();
            }
            if ("selectFavoriteTaskIds".equals(invocation.getMethod().getName())) {
                throw new AssertionError("task list favorites should avoid a large task_id IN query");
            }
            if ("selectList".equals(invocation.getMethod().getName())) {
                throw new AssertionError("task list favorites should only select task ids");
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        TaskLabelMapper taskLabelMapper = proxy(TaskLabelMapper.class, invocation -> {
            if ("selectLabelsForProject".equals(invocation.getMethod().getName())) {
                projectLabelCalls.incrementAndGet();
                return Collections.emptyList();
            }
            if ("selectLabelsForTaskIds".equals(invocation.getMethod().getName())) {
                throw new AssertionError("full project task list should load labels by project id");
            }
            if ("selectList".equals(invocation.getMethod().getName())) {
                throw new AssertionError("task list labels should be loaded with one join query");
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        LabelMapper labelMapper = proxy(LabelMapper.class, invocation -> defaultValue(invocation.getMethod().getReturnType()));
        LabelService labelService = new LabelService(labelMapper, taskLabelMapper, projectMemberMapper);
        TaskPermissionGuard permissionGuard = new TaskPermissionGuard(taskMapper, projectMemberMapper);
        TaskQueryService service = new TaskQueryService(
                taskMapper,
                taskFavoriteMapper,
                labelService,
                permissionGuard);

        List<TaskListItemResponse> list = service.listItemsByProjectId(7L, false, null, 9L);

        assertEquals(3, list.size());
        TaskListItemResponse parentItem = list.get(0);
        assertEquals(2, parentItem.getSubIssueCount());
        assertEquals(1, parentItem.getCompletedSubIssueCount());
        assertEquals(false, parentItem.getFavorited());
        String json = new ObjectMapper().writeValueAsString(list);
        assertFalse(json.contains("description"));
        assertFalse(json.contains("Large parent description"));
        assertFalse(json.contains("Large child description"));
        assertEquals(1, taskListItemCalls.get());
        assertEquals(1, favoriteTaskIdCalls.get());
        assertEquals(1, projectLabelCalls.get());
    }

    @Test
    void listItemsReturnsEmptyForProjectMemberWhenProjectHasNoTasks() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Task.class);
        AtomicInteger memberChecks = new AtomicInteger();

        TaskMapper taskMapper = proxy(TaskMapper.class, invocation -> {
            if ("selectListItemResponses".equals(invocation.getMethod().getName())) {
                return Collections.singletonList(null);
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        ProjectMemberMapper projectMemberMapper = proxy(ProjectMemberMapper.class, invocation -> {
            if ("selectCount".equals(invocation.getMethod().getName())) {
                memberChecks.incrementAndGet();
                return 1L;
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        TaskFavoriteMapper taskFavoriteMapper = proxy(TaskFavoriteMapper.class, invocation -> {
            throw new AssertionError("empty task lists should not load favorites");
        });
        TaskLabelMapper taskLabelMapper = proxy(TaskLabelMapper.class, invocation -> {
            throw new AssertionError("empty task lists should not load labels");
        });
        LabelMapper labelMapper = proxy(LabelMapper.class, invocation -> defaultValue(invocation.getMethod().getReturnType()));
        LabelService labelService = new LabelService(labelMapper, taskLabelMapper, projectMemberMapper);
        TaskPermissionGuard permissionGuard = new TaskPermissionGuard(taskMapper, projectMemberMapper);
        TaskQueryService service = new TaskQueryService(
                taskMapper,
                taskFavoriteMapper,
                labelService,
                permissionGuard);

        List<TaskListItemResponse> list = service.listItemsByProjectId(7L, false, null, 9L);

        assertEquals(Collections.emptyList(), list);
        assertEquals(1, memberChecks.get());
    }

    @Test
    void listItemsThrowsForbiddenForNonMemberWhenNoRowsAreVisible() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Task.class);

        TaskMapper taskMapper = proxy(TaskMapper.class, invocation -> {
            if ("selectListItemResponses".equals(invocation.getMethod().getName())) {
                return Collections.emptyList();
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        ProjectMemberMapper projectMemberMapper = proxy(ProjectMemberMapper.class, invocation -> {
            if ("selectCount".equals(invocation.getMethod().getName())) {
                return 0L;
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        TaskFavoriteMapper taskFavoriteMapper = proxy(TaskFavoriteMapper.class, invocation -> defaultValue(invocation.getMethod().getReturnType()));
        TaskLabelMapper taskLabelMapper = proxy(TaskLabelMapper.class, invocation -> defaultValue(invocation.getMethod().getReturnType()));
        LabelMapper labelMapper = proxy(LabelMapper.class, invocation -> defaultValue(invocation.getMethod().getReturnType()));
        LabelService labelService = new LabelService(labelMapper, taskLabelMapper, projectMemberMapper);
        TaskPermissionGuard permissionGuard = new TaskPermissionGuard(taskMapper, projectMemberMapper);
        TaskQueryService service = new TaskQueryService(
                taskMapper,
                taskFavoriteMapper,
                labelService,
                permissionGuard);

        assertThrows(ForbiddenOperationException.class, () -> service.listItemsByProjectId(7L, false, null, 9L));
    }

    @Test
    void searchTaskKeysRequiresSemanticSearchService() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Task.class);
        TaskMapper taskMapper = proxy(TaskMapper.class, invocation -> {
            return defaultValue(invocation.getMethod().getReturnType());
        });
        ProjectMemberMapper projectMemberMapper = proxy(ProjectMemberMapper.class, invocation -> {
            if ("selectCount".equals(invocation.getMethod().getName())) {
                return 1L;
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        TaskFavoriteMapper taskFavoriteMapper = proxy(TaskFavoriteMapper.class, invocation -> defaultValue(invocation.getMethod().getReturnType()));
        TaskLabelMapper taskLabelMapper = proxy(TaskLabelMapper.class, invocation -> defaultValue(invocation.getMethod().getReturnType()));
        LabelMapper labelMapper = proxy(LabelMapper.class, invocation -> defaultValue(invocation.getMethod().getReturnType()));
        LabelService labelService = new LabelService(labelMapper, taskLabelMapper, projectMemberMapper);
        TaskPermissionGuard permissionGuard = new TaskPermissionGuard(taskMapper, projectMemberMapper);
        TaskQueryService service = new TaskQueryService(taskMapper, taskFavoriteMapper, labelService, permissionGuard);

        assertThrows(IllegalStateException.class, () -> service.searchTaskKeys(7L, "semantic query", 9L));
    }

    @Test
    void searchTasksRanksTitleThenDescriptionThenVectorResults() {
        Task titleMatch = searchTask(1L, "ENG-1", "搜索原则", "[]");
        Task descriptionMatch = searchTask(
                2L,
                "ENG-2",
                "其他任务",
                """
                [{"type":"paragraph","content":[{"type":"text","text":"描述中的搜索词","styles":{}}],"children":[]}]
                """);
        Task vectorMatch = searchTask(3L, "ENG-3", "向量任务", "[]");
        TaskMapper taskMapper = proxy(TaskMapper.class, invocation -> {
            if ("selectList".equals(invocation.getMethod().getName())) {
                return List.of(titleMatch, descriptionMatch, vectorMatch);
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        ProjectMemberMapper projectMemberMapper = projectMemberMapper(9L, 7L);
        TaskSemanticSearchService semanticSearchService = mock(TaskSemanticSearchService.class);
        when(semanticSearchService.search(List.of(7L), "搜索")).thenReturn(List.of("ENG-3"));

        TaskQueryService service = searchService(taskMapper, projectMemberMapper, semanticSearchService);

        assertEquals(List.of("ENG-1", "ENG-2", "ENG-3"), service.searchTasks("搜索", 9L).stream()
                .map(Task::getTaskKey)
                .toList());
    }

    @Test
    void searchTasksMatchesVisibleBlockNoteDescriptionText() {
        Task descriptionMatch = searchTask(
                1L,
                "ENG-1",
                "其他任务",
                """
                [{"id":"block-1","type":"paragraph","props":{"textColor":"default"},"content":[{"type":"text","text":"遵循轻量原则","styles":{}}],"children":[]}]
                """);
        TaskMapper taskMapper = proxy(TaskMapper.class, invocation -> {
            if ("selectList".equals(invocation.getMethod().getName())) {
                return List.of(descriptionMatch);
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        ProjectMemberMapper projectMemberMapper = projectMemberMapper(9L, 7L);
        TaskSemanticSearchService semanticSearchService = mock(TaskSemanticSearchService.class);
        when(semanticSearchService.search(List.of(7L), "原则")).thenReturn(List.of());

        TaskQueryService service = searchService(taskMapper, projectMemberMapper, semanticSearchService);

        assertEquals(List.of("ENG-1"), service.searchTasks("原则", 9L).stream()
                .map(Task::getTaskKey)
                .toList());
    }

    @Test
    void searchTasksDeduplicatesTaskMatchedByLiteralAndVectorSearch() {
        Task sameTask = searchTask(1L, "ENG-1", "语义搜索", "[]");
        TaskMapper taskMapper = proxy(TaskMapper.class, invocation -> {
            if ("selectList".equals(invocation.getMethod().getName())) {
                return List.of(sameTask);
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        ProjectMemberMapper projectMemberMapper = projectMemberMapper(9L, 7L);
        TaskSemanticSearchService semanticSearchService = mock(TaskSemanticSearchService.class);
        when(semanticSearchService.search(List.of(7L), "搜索")).thenReturn(List.of("ENG-1"));

        TaskQueryService service = searchService(taskMapper, projectMemberMapper, semanticSearchService);

        assertEquals(List.of("ENG-1"), service.searchTasks("搜索", 9L).stream()
                .map(Task::getTaskKey)
                .toList());
    }

    @Test
    void searchTasksDoesNotFallBackToLiteralResultsWhenVectorSearchFails() {
        Task literalMatch = searchTask(1L, "ENG-1", "搜索原则", "[]");
        TaskMapper taskMapper = proxy(TaskMapper.class, invocation -> {
            if ("selectList".equals(invocation.getMethod().getName())) {
                return List.of(literalMatch);
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        ProjectMemberMapper projectMemberMapper = projectMemberMapper(9L, 7L);
        TaskSemanticSearchService semanticSearchService = mock(TaskSemanticSearchService.class);
        when(semanticSearchService.search(List.of(7L), "搜索"))
                .thenThrow(new IllegalStateException("Qdrant unavailable"));

        TaskQueryService service = searchService(taskMapper, projectMemberMapper, semanticSearchService);

        assertThrows(IllegalStateException.class, () -> service.searchTasks("搜索", 9L));
    }

    @Test
    void searchTasksDoesNotMatchInvisibleBlockNoteMetadata() {
        Task metadataOnly = searchTask(
                1L,
                "ENG-1",
                "其他任务",
                """
                [{"id":"secret-token","type":"paragraph","props":{"backgroundColor":"secret-token"},"content":[],"children":[]}]
                """);
        TaskMapper taskMapper = proxy(TaskMapper.class, invocation -> {
            if ("selectList".equals(invocation.getMethod().getName())) {
                return List.of(metadataOnly);
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
        ProjectMemberMapper projectMemberMapper = projectMemberMapper(9L, 7L);
        TaskSemanticSearchService semanticSearchService = mock(TaskSemanticSearchService.class);
        when(semanticSearchService.search(List.of(7L), "secret-token")).thenReturn(List.of());

        TaskQueryService service = searchService(taskMapper, projectMemberMapper, semanticSearchService);

        assertEquals(List.of(), service.searchTasks("secret-token", 9L));
    }

    private static TaskQueryService searchService(
            TaskMapper taskMapper,
            ProjectMemberMapper projectMemberMapper,
            TaskSemanticSearchService semanticSearchService) {
        TaskFavoriteMapper favoriteMapper = proxy(TaskFavoriteMapper.class,
                invocation -> defaultValue(invocation.getMethod().getReturnType()));
        TaskLabelMapper taskLabelMapper = proxy(TaskLabelMapper.class,
                invocation -> defaultValue(invocation.getMethod().getReturnType()));
        LabelMapper labelMapper = proxy(LabelMapper.class,
                invocation -> defaultValue(invocation.getMethod().getReturnType()));
        LabelService labelService = new LabelService(labelMapper, taskLabelMapper, projectMemberMapper);
        TaskPermissionGuard permissionGuard = new TaskPermissionGuard(taskMapper, projectMemberMapper);
        return new TaskQueryService(
                taskMapper, favoriteMapper, labelService, permissionGuard, semanticSearchService, projectMemberMapper);
    }

    private static ProjectMemberMapper projectMemberMapper(Long userId, Long projectId) {
        ProjectMember member = new ProjectMember();
        member.setUserId(userId);
        member.setProjectId(projectId);
        return proxy(ProjectMemberMapper.class, invocation -> {
            if ("selectList".equals(invocation.getMethod().getName())) {
                return List.of(member);
            }
            return defaultValue(invocation.getMethod().getReturnType());
        });
    }

    private static Task searchTask(Long id, String key, String title, String description) {
        Task task = new Task();
        task.setId(id);
        task.setTaskKey(key);
        task.setTitle(title);
        task.setDescription(description);
        task.setProjectId(7L);
        return task;
    }

    private static TaskListItemResponse task(Long id, String key, Long parentId, String status) {
        TaskListItemResponse task = new TaskListItemResponse();
        task.setId(id);
        task.setTaskKey(key);
        task.setTitle(key);
        task.setStatus(status);
        task.setPriority("medium");
        task.setProjectId(7L);
        task.setParentId(parentId);
        task.setProgressPercent(0);
        return task;
    }

    @FunctionalInterface
    private interface InvocationDelegate {
        Object invoke(InvocationCall invocation) throws Throwable;
    }

    private record InvocationCall(Method method, Object[] args) {
        Method getMethod() {
            return method;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationDelegate delegate) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return type.getSimpleName() + "Proxy";
                    }
                    return delegate.invoke(new InvocationCall(method, args));
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }
}
