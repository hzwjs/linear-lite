package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.CommentMention;
import com.linearlite.server.entity.InAppNotification;
import com.linearlite.server.entity.Label;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentFavorite;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.entity.ProjectEmailPreference;
import com.linearlite.server.entity.ProjectGitLabRepository;
import com.linearlite.server.entity.ProjectGitHubRepository;
import com.linearlite.server.entity.ProjectInvitation;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskActivity;
import com.linearlite.server.entity.TaskAttachment;
import com.linearlite.server.entity.TaskComment;
import com.linearlite.server.entity.TaskFavorite;
import com.linearlite.server.mapper.CommentMentionMapper;
import com.linearlite.server.mapper.InAppNotificationMapper;
import com.linearlite.server.mapper.LabelMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentFavoriteMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import com.linearlite.server.mapper.ProjectEmailPreferenceMapper;
import com.linearlite.server.mapper.ProjectInvitationMapper;
import com.linearlite.server.mapper.ProjectGitLabRepositoryMapper;
import com.linearlite.server.mapper.ProjectGitHubRepositoryMapper;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.ProjectTaskSeqMapper;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.TaskAttachmentMapper;
import com.linearlite.server.mapper.TaskCommentMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectLifecycleService {
    private final ProjectAccessGuard accessGuard;
    private final ProjectMapper projectMapper;
    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentFavoriteMapper documentFavoriteMapper;
    private final ProjectDocumentRevisionMapper revisionMapper;
    private final ProjectDocumentAttachmentService documentAttachmentService;
    private final TaskMapper taskMapper;
    private final TaskCommentMapper taskCommentMapper;
    private final CommentMentionMapper mentionMapper;
    private final InAppNotificationMapper notificationMapper;
    private final TaskActivityMapper activityMapper;
    private final TaskFavoriteMapper favoriteMapper;
    private final TaskAttachmentMapper attachmentMapper;
    private final LabelService labelService;
    private final LabelMapper labelMapper;
    private final ProjectInvitationMapper invitationMapper;
    private final ProjectMemberMapper memberMapper;
    private final ProjectEmailPreferenceMapper emailPreferenceMapper;
    private final ProjectGitLabRepositoryMapper gitLabRepositoryMapper;
    private final ProjectGitHubRepositoryMapper gitHubRepositoryMapper;
    private final ProjectTaskSeqMapper taskSeqMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public ProjectLifecycleService(
            ProjectAccessGuard accessGuard, ProjectMapper projectMapper,
            ProjectDocumentMapper documentMapper, ProjectDocumentRevisionMapper revisionMapper,
            ProjectDocumentFavoriteMapper documentFavoriteMapper,
            ProjectDocumentAttachmentService documentAttachmentService,
            TaskMapper taskMapper, TaskCommentMapper taskCommentMapper, CommentMentionMapper mentionMapper,
            InAppNotificationMapper notificationMapper, TaskActivityMapper activityMapper,
            TaskFavoriteMapper favoriteMapper, TaskAttachmentMapper attachmentMapper,
            LabelService labelService, LabelMapper labelMapper,
            ProjectInvitationMapper invitationMapper, ProjectMemberMapper memberMapper,
            ProjectEmailPreferenceMapper emailPreferenceMapper, ProjectGitLabRepositoryMapper gitLabRepositoryMapper,
            ProjectGitHubRepositoryMapper gitHubRepositoryMapper,
            ProjectTaskSeqMapper taskSeqMapper,
            ApplicationEventPublisher eventPublisher) {
        this.accessGuard = accessGuard;
        this.projectMapper = projectMapper;
        this.documentMapper = documentMapper;
        this.documentFavoriteMapper = documentFavoriteMapper;
        this.revisionMapper = revisionMapper;
        this.documentAttachmentService = documentAttachmentService;
        this.taskMapper = taskMapper;
        this.taskCommentMapper = taskCommentMapper;
        this.mentionMapper = mentionMapper;
        this.notificationMapper = notificationMapper;
        this.activityMapper = activityMapper;
        this.favoriteMapper = favoriteMapper;
        this.attachmentMapper = attachmentMapper;
        this.labelService = labelService;
        this.labelMapper = labelMapper;
        this.invitationMapper = invitationMapper;
        this.memberMapper = memberMapper;
        this.emailPreferenceMapper = emailPreferenceMapper;
        this.gitLabRepositoryMapper = gitLabRepositoryMapper;
        this.gitHubRepositoryMapper = gitHubRepositoryMapper;
        this.taskSeqMapper = taskSeqMapper;
        this.eventPublisher = eventPublisher;
    }

    /** 保留现有单元测试及扩展调用方的构造签名；生产 Bean 使用包含 GitHub mapper 的构造器。 */
    public ProjectLifecycleService(
            ProjectAccessGuard accessGuard, ProjectMapper projectMapper,
            ProjectDocumentMapper documentMapper, ProjectDocumentRevisionMapper revisionMapper,
            ProjectDocumentFavoriteMapper documentFavoriteMapper,
            ProjectDocumentAttachmentService documentAttachmentService,
            TaskMapper taskMapper, TaskCommentMapper taskCommentMapper, CommentMentionMapper mentionMapper,
            InAppNotificationMapper notificationMapper, TaskActivityMapper activityMapper,
            TaskFavoriteMapper favoriteMapper, TaskAttachmentMapper attachmentMapper,
            LabelService labelService, LabelMapper labelMapper,
            ProjectInvitationMapper invitationMapper, ProjectMemberMapper memberMapper,
            ProjectEmailPreferenceMapper emailPreferenceMapper, ProjectGitLabRepositoryMapper gitLabRepositoryMapper,
            ProjectTaskSeqMapper taskSeqMapper, ApplicationEventPublisher eventPublisher) {
        this(accessGuard, projectMapper, documentMapper, revisionMapper, documentFavoriteMapper,
                documentAttachmentService, taskMapper, taskCommentMapper, mentionMapper, notificationMapper,
                activityMapper, favoriteMapper, attachmentMapper, labelService, labelMapper, invitationMapper,
                memberMapper, emailPreferenceMapper, gitLabRepositoryMapper, null, taskSeqMapper, eventPublisher);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteProject(Long projectId, Long userId) {
        accessGuard.requireOwner(projectId, userId);
        documentMapper.lockProjectDocumentMutations(projectId);
        List<Long> documentIds = documentMapper.selectList(new LambdaQueryWrapper<ProjectDocument>()
                        .eq(ProjectDocument::getProjectId, projectId))
                .stream().map(ProjectDocument::getId).toList();
        if (!documentIds.isEmpty()) {
            // 先清理对象存储与附件元数据，再清理不可变版本和当前文档。
            documentAttachmentService.deleteForProject(projectId);
            documentFavoriteMapper.delete(new LambdaQueryWrapper<ProjectDocumentFavorite>()
                    .in(ProjectDocumentFavorite::getDocumentId, documentIds));
            revisionMapper.delete(new LambdaQueryWrapper<ProjectDocumentRevision>()
                    .in(ProjectDocumentRevision::getDocumentId, documentIds));
            documentMapper.deleteBatchIds(documentIds);
        }

        List<Long> taskIds = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getProjectId, projectId))
                .stream().map(Task::getId).toList();
        if (!taskIds.isEmpty()) {
            notificationMapper.delete(new LambdaQueryWrapper<InAppNotification>()
                    .in(InAppNotification::getTaskId, taskIds));
            List<Long> commentIds = taskCommentMapper.selectList(new LambdaQueryWrapper<TaskComment>()
                            .in(TaskComment::getTaskId, taskIds))
                    .stream().map(TaskComment::getId).toList();
            if (!commentIds.isEmpty()) {
                mentionMapper.delete(new LambdaQueryWrapper<CommentMention>()
                        .in(CommentMention::getCommentId, commentIds));
            }
            taskCommentMapper.delete(new LambdaQueryWrapper<TaskComment>().in(TaskComment::getTaskId, taskIds));
            labelService.deleteLinksForTaskIds(taskIds);
            activityMapper.delete(new LambdaQueryWrapper<TaskActivity>().in(TaskActivity::getTaskId, taskIds));
            favoriteMapper.delete(new LambdaQueryWrapper<TaskFavorite>().in(TaskFavorite::getTaskId, taskIds));
            attachmentMapper.delete(new LambdaQueryWrapper<TaskAttachment>().in(TaskAttachment::getTaskId, taskIds));
        }
        taskMapper.delete(new LambdaQueryWrapper<Task>().eq(Task::getProjectId, projectId));
        labelMapper.delete(new LambdaQueryWrapper<Label>().eq(Label::getProjectId, projectId));
        invitationMapper.delete(new LambdaQueryWrapper<ProjectInvitation>().eq(ProjectInvitation::getProjectId, projectId));
        emailPreferenceMapper.delete(new LambdaQueryWrapper<ProjectEmailPreference>()
                .eq(ProjectEmailPreference::getProjectId, projectId));
        gitLabRepositoryMapper.delete(new LambdaQueryWrapper<ProjectGitLabRepository>()
                .eq(ProjectGitLabRepository::getProjectId, projectId));
        if (gitHubRepositoryMapper != null) {
            gitHubRepositoryMapper.delete(new LambdaQueryWrapper<ProjectGitHubRepository>()
                    .eq(ProjectGitHubRepository::getProjectId, projectId));
        }
        taskSeqMapper.deleteByProjectId(projectId);
        memberMapper.delete(new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, projectId));
        projectMapper.deleteById(projectId);

        if (!taskIds.isEmpty()) {
            eventPublisher.publishEvent(new ProjectContentSemanticDeleteRequestedEvent(
                    ProjectContentType.TASK, taskIds));
        }
        if (!documentIds.isEmpty()) {
            eventPublisher.publishEvent(new ProjectContentSemanticDeleteRequestedEvent(
                    ProjectContentType.DOCUMENT, documentIds));
        }
    }
}
