export const BEIJING_TIME_ZONE = 'Asia/Shanghai'

const BEIJING_DATE_TIME_FORMATTER = new Intl.DateTimeFormat('zh-CN', {
  timeZone: BEIJING_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
})

const BEIJING_DATE_FORMATTER = new Intl.DateTimeFormat('zh-CN', {
  timeZone: BEIJING_TIME_ZONE,
  year: 'numeric',
  month: 'long',
  day: 'numeric'
})

/** 所有绝对时间统一按北京时间展示，避免跟随浏览器或服务器默认时区。 */
export function formatBeijingDateTime(value: string | number): string {
  return BEIJING_DATE_TIME_FORMATTER.format(new Date(value))
}

/** 日历日期不做时区换算；只有绝对时间进入本函数。 */
export function formatBeijingDate(value: string | number): string {
  return BEIJING_DATE_FORMATTER.format(new Date(value))
}

type RelativeTimeTranslator = (key: string, params?: { count: number }) => string

/** 所有绝对时间的相对时间文案统一从这里计算。输入必须是带时区的 ISO 时间或毫秒时间戳。 */
export function formatRelativeTime(
  value: string | number,
  t: RelativeTimeTranslator,
  prefix: 'documents.updatedTime' | 'taskEditor' = 'taskEditor',
  now = Date.now()
): string {
  const timestamp = typeof value === 'number' ? value : Date.parse(value)
  if (Number.isNaN(timestamp)) return ''
  const seconds = Math.max(0, Math.floor((now - timestamp) / 1000))
  if (seconds < 60) return t(`${prefix}.justNow`)
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return t(`${prefix}.minutesAgo`, { count: minutes })
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return t(`${prefix}.hoursAgo`, { count: hours })
  const days = Math.floor(hours / 24)
  if (days < 30) return t(`${prefix}.daysAgo`, { count: days })
  return t(`${prefix}.monthsAgo`, { count: Math.floor(days / 30) })
}
