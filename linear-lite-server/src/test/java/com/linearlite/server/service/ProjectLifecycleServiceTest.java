package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.mapper.CommentMentionMapper;
import com.linearlite.server.mapper.InAppNotificationMapper;
import com.linearlite.server.mapper.LabelMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentFavoriteMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import com.linearlite.server.mapper.ProjectEmailPreferenceMapper;
import com.linearlite.server.mapper.ProjectGitLabRepositoryMapper;
import com.linearlite.server.mapper.ProjectInvitationMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.ProjectTaskSeqMapper;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.TaskAttachmentMapper;
import com.linearlite.server.mapper.TaskCommentMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectLifecycleServiceTest {
    @Mock private ProjectAccessGuard accessGuard;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentFavoriteMapper documentFavoriteMapper;
    @Mock private ProjectDocumentRevisionMapper revisionMapper;
    @Mock private ProjectDocumentAttachmentService documentAttachmentService;
    @Mock private TaskMapper taskMapper;
    @Mock private TaskCommentMapper taskCommentMapper;
    @Mock private CommentMentionMapper mentionMapper;
    @Mock private InAppNotificationMapper notificationMapper;
    @Mock private TaskActivityMapper activityMapper;
    @Mock private TaskFavoriteMapper favoriteMapper;
    @Mock private TaskAttachmentMapper attachmentMapper;
    @Mock private LabelService labelService;
    @Mock private LabelMapper labelMapper;
    @Mock private ProjectInvitationMapper invitationMapper;
    @Mock private ProjectMemberMapper memberMapper;
    @Mock private ProjectEmailPreferenceMapper emailPreferenceMapper;
    @Mock private ProjectGitLabRepositoryMapper gitLabRepositoryMapper;
    @Mock private ProjectTaskSeqMapper taskSeqMapper;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ProjectLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new ProjectLifecycleService(
                accessGuard, projectMapper, documentMapper, revisionMapper, documentFavoriteMapper, documentAttachmentService,
                taskMapper, taskCommentMapper,
                mentionMapper, notificationMapper, activityMapper, favoriteMapper, attachmentMapper,
                labelService, labelMapper, invitationMapper, memberMapper, emailPreferenceMapper,
                gitLabRepositoryMapper,
                taskSeqMapper, eventPublisher);
    }

    @Test
    void deleteProjectRemovesRevisionsBeforeDocumentsAndPublishesDocumentDeletion() {
        ProjectDocument document = new ProjectDocument();
        document.setId(21L);
        document.setProjectId(3L);
        when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(document));
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.deleteProject(3L, 7L);

        verify(accessGuard).requireOwner(3L, 7L);
        InOrder deletionOrder = inOrder(revisionMapper, documentMapper, projectMapper);
        deletionOrder.verify(revisionMapper).delete(any(LambdaQueryWrapper.class));
        deletionOrder.verify(documentMapper).deleteBatchIds(List.of(21L));
        verify(gitLabRepositoryMapper).delete(any(LambdaQueryWrapper.class));
        deletionOrder.verify(projectMapper).deleteById(3L);
        verify(eventPublisher).publishEvent(new ProjectContentSemanticDeleteRequestedEvent(
                ProjectContentType.DOCUMENT, List.of(21L)));
    }
}
