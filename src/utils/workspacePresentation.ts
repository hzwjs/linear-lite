export interface WorkspacePresentation {
  preservePrimaryCanvas: boolean
  workspaceMode: 'none' | 'overlay'
}

export function resolveWorkspacePresentation(taskId: string | null | undefined): WorkspacePresentation {
  return {
    preservePrimaryCanvas: true,
    workspaceMode: taskId ? 'overlay' : 'none'
  }
}
