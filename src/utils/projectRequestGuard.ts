export function shouldIgnoreProjectResponse(
  requestSeq: number,
  currentSeq: number,
  activeProjectId: number | null | undefined,
  responseProjectId: number
): boolean {
  return requestSeq !== currentSeq || activeProjectId !== responseProjectId
}
