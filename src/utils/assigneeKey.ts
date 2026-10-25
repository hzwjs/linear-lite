export function userAssigneeKey(userId: number): string {
  return `user:${userId}`
}

export function userIdFromAssigneeKey(key: string | null | undefined): number | null {
  const match = /^user:([1-9]\d*)$/.exec(key ?? '')
  if (!match?.[1]) return null
  const userId = Number(match[1])
  return Number.isSafeInteger(userId) ? userId : null
}
