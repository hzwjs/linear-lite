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
