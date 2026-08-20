package com.linearlite.server.service;

import com.linearlite.server.entity.ProjectTaskSeq;
import com.linearlite.server.mapper.ProjectTaskSeqMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TaskSequenceServiceTest {

    private final ProjectTaskSeqMapper mapper = mock(ProjectTaskSeqMapper.class);
    private final TaskSequenceService service = new TaskSequenceService(mapper);

    @Test
    void repairsSequenceBehindExistingTasksBeforeReserving() {
        ProjectTaskSeq sequence = sequence(1028L);
        when(mapper.selectByProjectIdForUpdate(7L)).thenReturn(sequence);
        when(mapper.selectMaxTaskNumber(7L, "JLNX", 6)).thenReturn(1028L);

        long reserved = service.reserveTaskNumbers(7L, "JLNX", 1);

        assertEquals(1029L, reserved);
        verify(mapper).updateNextNumber(7L, 1030L);
    }

    @Test
    void keepsSequenceAheadOfExistingTasks() {
        ProjectTaskSeq sequence = sequence(1035L);
        when(mapper.selectByProjectIdForUpdate(7L)).thenReturn(sequence);
        when(mapper.selectMaxTaskNumber(7L, "JLNX", 6)).thenReturn(1028L);

        long reserved = service.reserveTaskNumbers(7L, "JLNX", 1);

        assertEquals(1035L, reserved);
        verify(mapper).updateNextNumber(7L, 1036L);
    }

    private static ProjectTaskSeq sequence(long nextNumber) {
        ProjectTaskSeq sequence = new ProjectTaskSeq();
        sequence.setProjectId(7L);
        sequence.setNextNumber(nextNumber);
        return sequence;
    }
}
