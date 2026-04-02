package com.linearlite.server.service;

import com.linearlite.server.dto.TaskLabelItemRequest;
import com.linearlite.server.entity.Label;
import com.linearlite.server.mapper.LabelMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.TaskLabelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabelServiceTest {

    @Mock
    private LabelMapper labelMapper;
    @Mock
    private TaskLabelMapper taskLabelMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;

    private LabelService labelService;

    @BeforeEach
    void setUp() {
        labelService = new LabelService(labelMapper, taskLabelMapper, projectMemberMapper);
    }

    @Test
    void replaceTaskLabelsRejectsItemWithBothIdAndName() {
        TaskLabelItemRequest item = new TaskLabelItemRequest();
        item.setId(1L);
        item.setName("x");

        assertThrows(
                IllegalArgumentException.class,
                () -> labelService.replaceTaskLabels(10L, 1L, List.of(item)));
    }

    @Test
    void replaceTaskLabelsRejectsItemWithNeitherIdNorName() {
        TaskLabelItemRequest item = new TaskLabelItemRequest();

        assertThrows(
                IllegalArgumentException.class,
                () -> labelService.replaceTaskLabels(10L, 1L, List.of(item)));
    }

    @Test
    void replaceTaskLabelsRejectsLabelFromOtherProject() {
        TaskLabelItemRequest item = new TaskLabelItemRequest();
        item.setId(5L);

        Label label = new Label();
        label.setId(5L);
        label.setProjectId(99L);
        label.setName("other");
        when(labelMapper.selectById(5L)).thenReturn(label);

        assertThrows(
                IllegalArgumentException.class,
                () -> labelService.replaceTaskLabels(10L, 1L, List.of(item)));
    }

    @Test
    void deleteLabelDefinitionRemovesTaskLinksAndLabelRow() {
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        Label label = new Label();
        label.setId(5L);
        label.setProjectId(1L);
        label.setName("bug");
        when(labelMapper.selectById(5L)).thenReturn(label);

        labelService.deleteLabelDefinition(1L, 5L, 7L);

        verify(taskLabelMapper).delete(any());
        verify(labelMapper).deleteById(5L);
    }

    @Test
    void deleteLabelDefinitionRejectsLabelFromOtherProject() {
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        Label label = new Label();
        label.setId(5L);
        label.setProjectId(99L);
        label.setName("x");
        when(labelMapper.selectById(5L)).thenReturn(label);

        assertThrows(
                IllegalArgumentException.class,
                () -> labelService.deleteLabelDefinition(1L, 5L, 7L));
    }
}
