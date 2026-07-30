const en = {
  app: {
    name: 'Linear Lite'
  },
  auth: {
    signIn: 'Log in',
    signUp: 'Sign up',
    subtitle: {
      login: 'Sign in to continue',
      register: 'Create your account with email verification'
    },
    tabs: {
      login: 'Log in',
      register: 'Sign up'
    },
    placeholder: {
      identity: 'Email or username',
      email: 'Email',
      verificationCode: 'Verification code',
      username: 'Username',
      password: 'Password'
    },
    sending: 'Sending...',
    sendCode: 'Send code',
    loading: {
      login: 'Signing in...',
      register: 'Creating account...'
    },
    action: {
      signIn: 'Sign in',
      signUp: 'Create account'
    },
    error: {
      enterEmail: 'Please enter email',
      sendCodeFailed: 'Failed to send code',
      enterCredentials: 'Please enter email or username and password',
      completeRegistration: 'Please complete all registration fields',
      authFailed: 'Authentication failed'
    }
  },
  common: {
    all: 'All',
    none: 'None',
    workspace: 'Workspace',
    unassigned: 'Unassigned',
    noProject: 'No Project',
    someone: 'Someone',
    status: 'Status',
    priority: 'Priority',
    assignee: 'Assignee',
    project: 'Project',
    title: 'Title',
    created: 'Created',
    updated: 'Updated',
    dueDate: 'Due date',
    plannedStartDate: 'Planned start',
    close: 'Close',
    cancel: 'Cancel',
    save: 'Save',
    attach: 'Attach',
    retry: 'Retry',
    remove: 'Remove',
    loading: 'Loading...',
    create: 'Create',
    creating: 'Creating...',
    board: 'Board',
    list: 'List',
    gantt: 'Gantt',
    filter: 'Filter',
    display: 'Display',
    labels: 'Labels',
    import: 'Import',
    language: 'Language'
  },
  sidebar: {
    favorites: 'Favorites',
    workspace: 'Workspace',
    projects: 'Projects',
    tasks: 'Tasks',
    documents: 'Documents',
    projectNavigation: '{project} navigation',
    newProjectTitle: 'New project',
    projectSettings: 'Project settings',
    analytics: 'Analytics',
    signOut: 'Sign out',
    hideSidebar: 'Hide sidebar',
    showSidebar: 'Show sidebar',
    search: 'Search'
  },
  projectModal: {
    title: 'New project',
    form: {
      nameLabel: 'Project name',
      identifierLabel: 'Identifier',
      namePlaceholder: 'e.g. Engineering',
      identifierPlaceholder: 'e.g. ENG (3 letters)'
    },
    buttons: {
      create: 'Create',
      creating: 'Creating...'
    },
    validation: {
      nameAndIdentifierRequired: 'Please enter project name and identifier',
      identifierTooLong: 'Identifier must be at most 16 characters',
      createFailed: 'Create failed'
    }
  },
  projectSettingsModal: {
    title: 'Project settings',
    basicTitle: 'Basic information',
    inviteTitle: 'Invite by email',
    inviteDescription: 'Members can access this project after signing in.',
    inviteButton: 'Invite',
    inviting: 'Inviting...',
    inviteSuccess: 'Invitation sent.',
    invitePlaceholder: "name{'@'}example.com",
    importTitle: 'Task import',
    importDescription: 'Import tasks from CSV or Excel.',
    importButton: 'Import tasks',
    emailTitle: 'Email notifications',
    emailDescription: 'Send assignees a summary of due and overdue tasks.',
    dailySummary: 'Daily summary',
    deleteTitle: 'Delete project',
    deleteDescription: 'Delete this project and all its tasks. This cannot be undone.',
    deleteButton: 'Delete project',
    deleting: 'Deleting...',
    deleteConfirm: 'Delete project \"{name}\" and all its tasks? This cannot be undone.',
    errors: {
      updateFailed: 'Update failed',
      deleteFailed: 'Delete failed',
      emailRequired: 'Please enter an email to invite',
      inviteFailed: 'Invite failed'
    },
    buttons: {
      save: 'Save',
      saving: 'Saving...'
    }
  },
  emptyState: {
    noProjects: 'No projects yet',
    selectProject: 'Select a project from the left'
  },
  documents: {
    title: 'Documents', issues: 'Issues', workspaceNavigation: 'Project workspace',
    newRoot: 'New document', newChild: 'New child document', untitled: 'Untitled',
    filterLabel: 'Filter documents by title', filterPlaceholder: 'Filter documents…', clearFilter: 'Clear document filter',
    loadingTree: 'Loading documents…', loadingDocument: 'Loading document…', loadFailed: 'Documents could not be loaded.',
    emptyTree: 'Keep project knowledge alongside the work.', createFirst: 'Create the first document',
    selectDocument: 'Select a document or create a new one.', noSearchResults: 'No documents match this title.', treeLabel: 'Project document tree',
    expand: 'Expand document', collapse: 'Collapse document', actionsFor: 'Actions for {title}',
    dragHint: 'Drag the document row above, inside, or below another document. Use Alt plus the arrow keys to move with the keyboard.',
    dropBefore: 'Place above “{title}”', dropInside: 'Move inside “{title}”', dropAfter: 'Place below “{title}”',
    archive: 'Archive', archiveConfirm: 'Archive “{title}” and all of its child documents?',
    favorites: 'Favorites', addFavorite: 'Add to favorites', removeFavorite: 'Remove from favorites',
    archived: 'Archived', archivedDescription: 'Archived document subtrees retain their original structure.', noArchived: 'No archived documents.', restore: 'Restore',
    history: 'History', version: 'Version {version}', backToHistory: 'Back to version history', historyLoadFailed: 'Version history could not be loaded.', noHistory: 'No versions yet.',
    restoreThisVersion: 'Restore this version', restoring: 'Restoring…', documentTitle: 'Document title',
    bodyPlaceholder: 'Write project context, decisions, and plans…', breadcrumbLabel: 'Document location',
    conflictTitle: 'A newer version exists on the server',
    conflictDescription: 'Your draft is preserved. The server is now at version {version}. Copy the draft or reload before continuing.',
    copyDraft: 'Copy draft', copied: 'Copied', reloadServerVersion: 'Reload server version',
    saveFailedTitle: 'Changes could not be saved', saveFailedDescription: 'Your draft remains open. Retry when the connection is available.',
    mentionMembersGroup: 'Members', mentionDocumentsGroup: 'Documents', mentionNoMatches: 'No members or documents match.',
    attachmentDocumentMismatch: 'This attachment does not belong to the current document. The download was blocked.',
    mobile: { title: 'Project document', readonly: 'Read only', back: 'Back', loading: 'Loading document…', loadFailed: 'Document could not be loaded', projectMismatch: 'This document does not belong to the current project.', bodyLabel: 'Document content' },
    saveState: { idle: 'Not edited', dirty: 'Unsaved changes', saving: 'Saving…', saved: 'Saved', conflict: 'Save conflict', failed: 'Save failed' }
  },
  globalSearch: {
    title: 'Search project content', placeholder: 'Search tasks and documents across projects…', searchAria: 'Search tasks and documents across projects', close: 'Close search',
    loading: 'Searching related content…', unavailableTitle: 'Search is temporarily unavailable', unavailableDescription: 'Check the connection and try again.',
    emptyTitle: 'Search across projects', emptyDescription: 'Find tasks and documents by words in their title or content.',
    noResultsTitle: 'No related content found', noResultsDescription: 'Try a shorter or different search.', resultsAria: 'Search results',
    resultsCount: '{count} related results', type: { task: 'Task', document: 'Document' }, selectHint: 'Select', openHint: 'Open', closeHint: 'Close'
  },
  command: {
    newTask: 'New task',
    viewBoard: 'Switch to Board view',
    viewList: 'Switch to List view',
    projectSettings: 'Open project settings',
    focusSearch: 'Focus search',
    toggleSidebar: 'Toggle sidebar visibility'
  },
  commandPalette: {
    title: 'Command palette',
    searchPlaceholder: 'Type a command or search...',
    nestedSearchPlaceholder: 'Search…',
    searchAria: 'Search commands',
    noMatches: 'No commands match.',
    backAria: 'Back'
  },
  boardView: {
    newIssue: 'New issue',
    searchIssues: 'Search titles and descriptions...',
    allIssues: 'All issues',
    active: 'Active',
    backlog: 'Backlog',
    filterOptions: 'Filter options',
    filterSectionTitle: 'Filter',
    addFilterPlaceholder: 'Add filter…',
    filterSubmenuAria: 'Filter submenu',
    labelsLoadFailed: 'Failed to load labels',
    labelsNeedProject: 'Select a project first',
    noLabelsMatch: 'No labels match',
    searchLabels: 'Search labels…',
    labelsLoading: 'Loading labels…',
    labels: 'Labels',
    labelsCount: 'labels',
    statusCount: 'statuses',
    priorityCount: 'priorities',
    assigneeCount: 'assignees',
    filterOp: {
      is: 'is',
      includeAnyOf: 'include any of'
    },
    activeFilterChipsAria: 'Active filters',
    viewSectionTitle: 'View',
    clearIssueFilters: 'Clear filters',
    assigneeFilterHint:
      'Tasks need a system assignee ID; imported issues count if the display name exactly matches the user’s username (case-insensitive).',
    filterBadge: 'Filters · {n}',
    filterButtonAriaActive: 'Filter ({n} active)',
    orderAscTitle: 'Ascending: older, smaller, A→Z',
    orderDescTitle: 'Descending: newer, larger, Z→A',
    noTasksMatchSearchOnly: 'No issues match your search.',
    noTasksMatchIssueFiltersOnly: 'No issues match status / priority / assignee / label filters.',
    noTasksMatchSearchAndFilters: 'No issues match both your search and filters.',
    filterByStatus: 'Filter by status',
    filterByPriority: 'Filter by priority',
    assigneeQuickFilter: 'Quick assignee filter',
    filterByAssignee: 'Filter by assignee',
    groupBy: 'Group by',
    groupTasks: 'Group tasks',
    sort: 'Sort',
    orderTasks: 'Order tasks',
    completed: 'Completed',
    completedVisibility: 'Completed visibility',
    allStatus: 'All Status',
    allPriorities: 'All Priorities',
    allAssignees: 'All assignees',
    group: 'Group',
    orderAsc: '↑ Asc',
    orderDesc: '↓ Desc',
    emptyGroups: 'Empty groups',
    displayOptions: 'Display options',
    showOnIssue: 'Show on issue',
    subIssues: 'Sub-issues',
    showSubIssues: 'Show sub-issues',
    nestedSubIssues: 'Nested sub-issues',
    loadingTasks: 'Loading tasks...',
    noTasks: "You don't have any tasks yet.",
    createFirstTask: 'Create your first task',
    noTasksMatchFilters: 'No tasks match your filters.',
    clearFilters: 'Clear filters',
    addIssue: 'Add issue',
    addIssueToColumn: 'Add issue to this column',
    openOnly: 'Hide done & backlog',
    id: 'ID',
    progress: 'Progress',
    plannedStart: 'Planned start',
    ganttEmpty: 'No top-level tasks in the current result can be shown on the Gantt chart.'
  },
  issueComposer: {
    dialogLabel: 'Create issue',
    title: 'New issue',
    issueTitlePlaceholder: 'Issue title',
    descriptionPlaceholder: 'Add description... Type / for formatting',
    createMore: 'Create more',
    createIssue: 'Create issue',
    creatingIssue: 'Creating...'
  },
  attachments: {
    fileTooLargeSkipped: 'exceeds {size} and was skipped',
    uploadFailed: 'Upload failed',
    uploading: 'Uploading...',
    downloadFailed: 'Download failed',
    deleteFailed: 'Delete failed'
  },
  taskImage: {
    altFallback: 'image'
  },
  editor: {
    placeholder: 'Write something...',
    slashMenu: {
      ariaLabel: 'Block type menu',
      heading1: 'Heading 1',
      heading2: 'Heading 2',
      heading3: 'Heading 3',
      bulletList: 'Bulleted list',
      orderedList: 'Numbered list',
      taskList: 'Checklist',
      codeBlock: 'Code block',
      blockquote: 'Blockquote'
    },
    codeBlock: {
      languageAria: 'Code block language',
      copyAria: 'Copy code',
      languages: {
        plainText: 'Plain text',
        javascript: 'JavaScript',
        typescript: 'TypeScript',
        sql: 'SQL',
        json: 'JSON',
        html: 'HTML',
        css: 'CSS',
        bash: 'Bash',
        python: 'Python',
        java: 'Java',
        xml: 'XML'
      }
    }
  },
  select: {
    placeholder: 'Select…',
    ariaLabel: 'Select option',
    searchAria: 'Search',
    filterPlaceholder: 'Type to filter…'
  },
  assigneeSelect: {
    searchPlaceholder: 'Search assignees…',
    noResults: 'No matching members'
  },
  datePicker: {
    placeholder: 'Select date',
    triggerAria: 'Due date',
    dialogAria: 'Choose date',
    previousMonth: 'Previous month',
    nextMonth: 'Next month',
    today: 'Today',
    todayAria: 'Today, {day}',
    weekdays: {
      mon: 'Mon',
      tue: 'Tue',
      wed: 'Wed',
      thu: 'Thu',
      fri: 'Fri',
      sat: 'Sat',
      sun: 'Sun'
    }
  },
  taskStore: {
    errors: {
      loadFailed: 'Failed to load tasks.',
      noProject: 'No project selected.',
      createFailed: 'Failed to create task.',
      updateFailed: 'Failed to update task.'
    }
  },
  taskImport: {
    errors: {
      maxRows: 'Import supports up to {count} rows per file.',
      titleColumnRequired: 'Title column is required.',
      importIdColumnRequired: 'Import ID column is required.',
      titleRequired: 'Title is required.',
      importIdRequired: 'Import ID is required.',
      importIdUnique: 'Import ID must be unique within the file.',
      invalidStatus: 'Status must be one of: {values}.',
      invalidPriority: 'Priority must be one of: {values}.',
      invalidDueDate: 'Due date must use YYYY-MM-DD.',
      parentSelfReference: 'Parent Import ID cannot reference the same row.',
      parentMissing: 'Parent Import ID must reference another row in the same file.',
      unsupportedFileType: 'Only .csv and .xlsx files are supported.',
      noSheets: 'The file does not contain any sheets.',
      firstSheetUnreadable: 'The first sheet could not be read.',
      missingHeader: 'The file must include a header row.',
      invalidPlannedStartDate: 'Planned start date must use YYYY-MM-DD.',
      invalidProgress: 'Progress must be an integer from 0 to 100 (optional % suffix).'
    }
  },
  taskImportModal: {
    ariaLabel: 'Import tasks',
    title: 'Task import',
    subtitle: 'Import issues from CSV or Excel',
    steps: {
      upload: 'Upload',
      mapping: 'Mapping',
      preview: 'Preview',
      result: 'Result'
    },
    downloadTemplate: 'Download template',
    dropzone: {
      title: 'Choose a `.csv` or `.xlsx` file',
      copy: 'Required template columns: `title`, `importId`. Optional: `parentImportId`, `description`, `status`, `priority`, `assignee`, `plannedStartDate`, `dueDate`, `progressPercent`.'
    },
    fileMeta: {
      file: 'File',
      rows: 'Rows',
      project: 'Project',
      noProject: 'No project selected'
    },
    mapping: {
      required: 'Required',
      unmapped: 'Unmapped'
    },
    fields: {
      importId: 'Import ID',
      parentImportId: 'Parent Import ID',
      progressPercent: 'Progress %',
      plannedStartDate: 'Planned start'
    },
    preview: {
      summary: {
        total: 'Total',
        parents: 'Parents',
        subtasks: 'Subtasks'
      },
      table: {
        parent: 'Parent',
        topLevel: 'Top-level'
      },
      errors: {
        lineMessage: 'Line {lineNumber} · {field} · {message}'
      }
    },
    result: {
      success: 'Imported {count} issues into the current project.',
      summary: {
        parents: 'Parents',
        subtasks: 'Subtasks',
        created: 'Created'
      }
    },
    footer: {
      back: 'Back',
      review: 'Review import',
      importIssues: 'Import issues',
      importing: 'Importing...',
      done: 'Done'
    },
    errors: {
      parseFailed: 'Failed to parse file.',
      importFailed: 'Import failed.'
    }
  },
  taskEditor: {
    workspaceAria: 'Issue workspace',
    breadcrumbAria: 'Breadcrumb',
    newIssue: 'New issue',
    issue: 'Issue',
    execution: 'Execution',
    time: 'Time',
    archive: 'Archive',
    properties: 'Properties',
    subIssueOf: 'Sub-issue of {id} {title}',
    addToFavorites: 'Add to favorites',
    removeFromFavorites: 'Remove from favorites',
    saved: 'Saved',
    saving: 'Saving...',
    saveFailed: 'Save failed',
    retrySave: 'Retry',
    previousIssue: 'Previous issue',
    nextIssue: 'Next issue',
    enterFullscreen: 'Fullscreen',
    exitFullscreen: 'Exit fullscreen',
    issueTitlePlaceholder: 'Issue title',
    descriptionPlaceholder: 'Add context or notes… Type / for headings, lists, and more',
    mermaidPreview: 'Mermaid preview',
    attachments: 'Attachments',
    attachmentsUploading: 'Uploading attachment…',
    deleteAttachment: 'Delete attachment',
    noAttachments: 'No attachments. Use the paperclip to add one.',
    subIssues: 'Sub-issues',
    noSubIssues: 'No sub-issues',
    createNewSubIssue: 'Create new sub-issue',
    nestedSubIssues: 'Nested sub-issues',
    subIssueDescriptionPlaceholder: 'Add description…',
    expandSubIssues: 'Expand sub-issues',
    collapseSubIssues: 'Collapse sub-issues',
    discard: 'Discard',
    createSubIssue: 'Create',
    creatingSubIssue: 'Creating...',
    activity: 'Activity',
    unsubscribe: 'Unsubscribe',
    loadingActivity: 'Loading activity...',
    noActivityYet: 'No activity yet.',
    createdIssueSuffix: 'created the issue',
    leaveComment: 'Leave a comment...',
    commentAria: 'Comment',
    sendAria: 'Send',
    commentShortcutHint: 'Press Cmd+Enter or Ctrl+Enter to send.',
    setPriority: 'Set priority',
    addLabel: 'Add label',
    removeLabel: 'Remove from this task',
    deleteProjectLabelDefinition: 'Delete this label from the project',
    addToProject: 'Add to project',
    noProject: 'No project',
    completedAt: 'Completed at',
    progress: 'Progress',
    progressAria: 'Completion progress from 0 to 100 percent',
    progressCompletesTask: 'Progress reached 100%; status updated to Done.',
    progressReopensTask: 'Progress dropped below 100%; status updated to In progress.',
    dueToday: 'Due today',
    dueInDays: 'Due in {count} day(s)',
    dueOverdueDays: 'Overdue by {count} day(s)',
    justNow: 'just now',
    minutesAgo: '{count}m ago',
    hoursAgo: '{count}h ago',
    daysAgo: '{count}d ago',
    monthsAgo: '{count}mo ago',
    importedAssigneeLine: 'Imported assignee: {name}',
    comments: 'Comments',
    commentsLoading: 'Loading comments…',
    noComments: 'No comments yet.',
    reply: 'Reply',
    react: 'React',
    replyPlaceholder: 'Write a reply...',
    viewMoreReplies: 'View {count} more replies',
    hideReplies: 'Hide replies',
    notifyMembers: 'Notify members (click to toggle)',
    mentionNoMatches: 'No matching members',
    commentSendFailed: 'Failed to send',
    deleteComment: 'Delete',
    deleteCommentAria: 'Delete this comment',
    deleteCommentConfirm: 'Delete this comment? This action cannot be undone.',
    saveFailedBlockClose: 'Save failed. Resolve the issue and try again before leaving this task.',
    saveTimeoutWarn: 'Save is taking longer than expected. Your latest edits may not be fully synced yet.'
  },
  notifications: {
    title: 'Notifications',
    empty: 'No notifications',
    markAllRead: 'Mark all read',
    mentionInIssue: 'Mentioned you on {key}'
  },
  taskList: {
    changeStatus: 'Change status',
    createIssueInGroup: 'Create issue in group',
    copyTitle: 'Copy title',
    titleCopied: 'Copied',
    markDone: 'Mark done',
    markNotDone: 'Mark not done',
    addSubIssue: 'Add sub-issue',
    expandSubtasks: 'Expand subtasks',
    collapseSubtasks: 'Collapse subtasks',
    expandAllSubtasks: 'Expand all',
    collapseAllSubtasks: 'Collapse all',
    columnPlannedStart: 'Planned start',
    columnDueDate: 'Due date',
    columnProgress: 'Progress',
    changeAssignee: 'Change assignee',
    assigneeSearchPlaceholder: 'Search members',
    clearDate: 'Clear date',
    progressDragAria: 'Drag or use arrow keys to adjust progress',
    bulk: {
      assignTo: 'Assign to…',
      assignToMe: 'Assign to me',
      changeStatus: 'Change status…',
      changePriority: 'Change priority…',
      changeLabels: 'Change or add labels…',
      setDueDate: 'Set due date…',
      selectedCount: '{n} selected',
      issuesBadge: '{n} issues',
      nestedAssign: 'Assign to',
      nestedStatus: 'Change status',
      nestedPriority: 'Change priority',
      nestedLabels: 'Add label',
      nestedDueDate: 'Set due date',
      dueClear: 'Clear due date',
      dueToday: 'Due today',
      dueEndOfWeek: 'Due end of week',
      labelsLoading: 'Loading labels…',
      labelsEmpty: 'No labels in this project',
      labelsNeedProject: 'Select a project first',
      actions: 'Actions'
    }
  },
  status: {
    backlog: 'Backlog',
    todo: 'Todo',
    in_progress: 'In Progress',
    in_review: 'In Review',
    done: 'Done',
    canceled: 'Canceled',
    duplicate: 'Duplicate'
  },
  priority: {
    low: 'Low',
    medium: 'Medium',
    high: 'High',
    urgent: 'Urgent'
  },
  fieldLabel: {
    assigneeId: 'assignee',
    progressPercent: 'progress',
    dueDate: 'due date',
    plannedStartDate: 'planned start',
    title: 'title',
    description: 'description',
    status: 'status',
    priority: 'priority',
    labels: 'labels',
    default: 'field'
  },
  activity: {
    created: '{actor} created the issue',
    favorited: '{actor} favorited the issue',
    unfavorited: '{actor} removed the issue from favorites',
    changedField: '{actor} changed {field}',
    changedFromTo: '{actor} changed {field} from {oldValue} to {newValue}',
    addedLabels: '{actor} added labels',
    removedLabels: '{actor} removed labels',
    updatedLabels: '{actor} updated labels',
    updated: '{actor} updated the issue',
    emptyValue: 'empty',
    labelListSeparator: ', '
  },
  analytics: {
    title: 'Project Analytics',
    purpose: 'Assess project health, locate risk, and open the work that needs attention',
    filterLabel: 'Analytics range',
    granularityLabel: 'Review period',
    previousPeriod: 'Previous period',
    nextPeriod: 'Next period',
    rangeStart: 'Start date',
    rangeEnd: 'End date',
    granularity: {
      day: 'Day',
      week: 'Week',
      month: 'Month',
      year: 'Year'
    },
    dateRange: 'Date range',
    dateSingle: 'Date',
    yearSingle: 'Year',
    trend: 'Trend',
    trendAccessibleLabel: 'Created, completed, and due task trend',
    created: 'Created',
    completed: 'Completed',
    due: 'Due',
    overdueNow: 'Overdue now',
    currentProject: 'Current project',
    keyMetrics: 'Drill-down metrics',
    reviewConclusion: '{period} review',
    period: {
      day: 'Daily',
      week: 'Weekly',
      month: 'Monthly',
      year: 'Annual'
    },
    health: {
      healthy: {
        title: 'Delivery pace is stable',
        detail: 'There are no overdue tasks and completions are keeping up with intake. Net backlog reduced by {count}.'
      },
      attention: {
        title: 'Intake is outpacing delivery',
        detail: 'There are no overdue tasks, but created tasks exceed completions by {count}. Watch backlog growth.'
      },
      critical: {
        title: 'Overdue work needs attention',
        detail: 'This project has {count} overdue tasks. Open the overdue queue to confirm ownership and next steps.'
      }
    },
    netFlowValue: 'Net intake {value}',
    deliveryRhythm: 'Delivery rhythm',
    deliveryRhythmDescription: 'Compare task intake, completion, and due dates to spot backlog changes.',
    riskLocation: 'Risk location',
    riskLocationDescription: 'Review current workflow backlog and team delivery during this period.',
    workflowSnapshot: 'Current workflow',
    workflowSnapshotDescription: 'All project tasks, independent of the selected period',
    activeTasks: 'Open',
    teamThroughput: 'Team delivery',
    teamThroughputDescription: 'Ownership and completion of tasks created during this period',
    assigneeCount: '{count} assignees',
    assigneeResult: '{completed}/{total} completed',
    inProgressCount: '{count} in progress',
    actionQueue: 'Action queue',
    actionQueueDescription: 'Metrics and task details share one definition. Open a task to take action.',
    metric: {
      created: 'Created in period',
      completed: 'Completed in period',
      due: 'Due in period',
      overdue: 'Overdue now'
    },
    task: 'Task',
    noMatchingTasks: 'No matching tasks',
    noMatchingTasksDescription: 'Choose another metric or adjust the review period.',
    pageOf: 'Page {page} of {total}',
    currentSnapshot: 'Current Snapshot',
    totalTasks: 'Total tasks',
    overdue: 'Overdue',
    statusBreakdown: 'Status Distribution',
    assigneeBreakdown: 'Assignee Distribution',
    priorityBreakdown: 'Priority Distribution',
    taskList: 'Task Details',
    dayFocus: 'Daily Key Metrics',
    allTasks: 'All Tasks',
    createdToday: 'Created Today',
    completedToday: 'Completed Today',
    dueToday: 'Due Today',
    noData: 'No data',
    loading: 'Loading...',
    loadError: 'Load failed',
    retry: 'Retry',
    netChange: 'Net change',
    unassigned: 'Unassigned',
    page: 'Page {page}',
    totalItems: '{total} items',
    prevPage: 'Previous',
    nextPage: 'Next'
  }
} as const

export default en
