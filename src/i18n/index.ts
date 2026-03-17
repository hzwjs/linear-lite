import { createI18n } from 'vue-i18n'
import zhCN from './messages/zh-CN'
import en from './messages/en'

const messages = {
  'zh-CN': zhCN,
  en
} as const

export const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages
})
