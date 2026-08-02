/**
 * 将文本写入剪贴板。
 *
 * 优先使用异步 Clipboard API；在非安全上下文（http 非 localhost、iframe 无
 * clipboard 权限）下 navigator.clipboard 不存在或被拒绝，此时降级为
 * textarea + document.execCommand('copy') 的老式方案。
 *
 * @returns 是否复制成功
 */
export async function copyTextToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // 降级路径：隐藏 textarea + execCommand（非安全上下文可用）
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.left = '-9999px'
    document.body.appendChild(ta)
    ta.select()
    try {
      return document.execCommand('copy')
    } catch {
      return false
    } finally {
      document.body.removeChild(ta)
    }
  }
}
