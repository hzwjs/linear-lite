package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.InAppNotification;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.InAppNotificationMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InAppNotificationServiceTest {

    @Mock
    private InAppNotificationMapper inAppNotificationMapper;
    @Mock
    private TaskMapper taskMapper;

    private InAppNotificationService service;

    @BeforeEach
    void setUp() {
        service = new InAppNotificationService(inAppNotificationMapper, taskMapper);
    }

    @Test
    void markReadRejectsOtherUserNotification() {
        InAppNotification n = new InAppNotification();
        n.setId(1L);
        n.setUserId(10L);
        when(inAppNotificationMapper.selectById(1L)).thenReturn(n);

        assertThrows(ForbiddenOperationException.class, () -> service.markRead(99L, 1L));
    }

    @Test
    void markReadUpdatesWhenUnread() {
        InAppNotification n = new InAppNotification();
        n.setId(1L);
        n.setUserId(10L);
        n.setReadAt(null);
        when(inAppNotificationMapper.selectById(1L)).thenReturn(n);

        service.markRead(10L, 1L);

        verify(inAppNotificationMapper).updateById(any(InAppNotification.class));
    }

    @Test
    void countUnreadDelegatesToMapper() {
        when(inAppNotificationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        assertEquals(3L, service.countUnread(10L));
    }
}
