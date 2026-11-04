package com.linearlite.server.service;

import java.util.List;

/**
 * 生成在目标服务器上执行一次的接入脚本。所有可变片段（项目标识、target_id、账号）只含
 * [A-Za-z0-9_-]，CA 公钥放在带引号的 heredoc 中，因此脚本内容不会被 shell 再次解释。
 *
 * 多个项目可以共用同一台服务器：每个项目的 CA 与 principal 分别落在 ca.d、principals.d 下，
 * 脚本重新汇总为 sshd 实际读取的 user_ca.pub 与 principals/&lt;账号&gt;。
 */
final class AccessScript {

    private AccessScript() {
    }

    static String render(String project, String targetId, List<String> caPublicKeys, List<String> loginUsers) {
        StringBuilder script = new StringBuilder();
        script.append("""
                #!/bin/sh
                # Linear Lite 远程终端接入脚本：项目 @PROJECT@ / 服务器 @TARGET@。
                # 需要 root 执行；可重复执行，内容以本次脚本为准。执行前请先审阅。
                set -eu
                BASE=/etc/ssh/linear-lite
                CONF_NAME=60-linear-lite.conf
                [ "$(id -u)" -eq 0 ] || { echo "请使用 root 执行" >&2; exit 1; }

                install -d -m 755 "$BASE" "$BASE/ca.d" "$BASE/principals.d" "$BASE/principals"

                # 1. 项目 CA 公钥（轮换期间包含新旧两行）
                cat > "$BASE/ca.d/@PROJECT@.pub" <<'LINEAR_LITE_CA'
                """.replace("@PROJECT@", project).replace("@TARGET@", targetId));
        for (String key : caPublicKeys) {
            script.append(key.trim()).append('\n');
        }
        script.append("LINEAR_LITE_CA\n\n");
        script.append("# 2. 本机允许的 principal：只列出属于该项目、该服务器的登录账号\n");
        script.append("rm -f \"$BASE/principals.d/").append(project).append('@').append(targetId).append("@\"*\n");
        for (String user : loginUsers) {
            script.append("printf '%s\\n' 'rt.").append(project).append('.').append(targetId).append('.').append(user)
                    .append("' > \"$BASE/principals.d/").append(project).append('@').append(targetId).append('@')
                    .append(user).append("\"\n");
        }
        script.append("""

                # 3. 汇总为 sshd 读取的文件
                cat "$BASE"/ca.d/*.pub > "$BASE/user_ca.pub.new"
                mv "$BASE/user_ca.pub.new" "$BASE/user_ca.pub"
                rm -f "$BASE"/principals/*
                for u in $(ls "$BASE/principals.d" | awk -F@ 'NF==3 {print $3}' | sort -u); do
                  cat "$BASE"/principals.d/*@"$u" > "$BASE/principals/$u"
                done
                chmod 644 "$BASE/user_ca.pub" "$BASE"/principals/* 2>/dev/null || true

                # 4. sshd 配置：优先使用 sshd_config.d 的 drop-in；旧系统追加到 sshd_config
                WANT_CA="TrustedUserCAKeys $BASE/user_ca.pub"
                WANT_PR="AuthorizedPrincipalsFile $BASE/principals/%u"
                if [ -d /etc/ssh/sshd_config.d ] && grep -Eq '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config.d/\\*\\.conf' /etc/ssh/sshd_config; then
                  TARGET_FILE="/etc/ssh/sshd_config.d/$CONF_NAME"
                  printf '%s\\n%s\\n' "$WANT_CA" "$WANT_PR" > "$TARGET_FILE.new"
                  [ -f "$TARGET_FILE" ] && cp -p "$TARGET_FILE" "$TARGET_FILE.bak" || rm -f "$TARGET_FILE.bak"
                  mv "$TARGET_FILE.new" "$TARGET_FILE"
                else
                  TARGET_FILE=/etc/ssh/sshd_config
                  cp -p "$TARGET_FILE" "$TARGET_FILE.linear-lite.bak"
                  if ! grep -q '^# linear-lite remote terminal' "$TARGET_FILE"; then
                    printf '\\n# linear-lite remote terminal\\n%s\\n%s\\n' "$WANT_CA" "$WANT_PR" >> "$TARGET_FILE"
                  fi
                fi

                restore() {
                  if [ "$TARGET_FILE" = /etc/ssh/sshd_config ]; then
                    mv "$TARGET_FILE.linear-lite.bak" "$TARGET_FILE"
                  elif [ -f "$TARGET_FILE.bak" ]; then
                    mv "$TARGET_FILE.bak" "$TARGET_FILE"
                  else
                    rm -f "$TARGET_FILE"
                  fi
                }
                if ! sshd -t; then
                  restore
                  echo "sshd 配置校验失败，已恢复原配置" >&2
                  exit 1
                fi
                if ! sshd -T | grep -qi "^trustedusercakeys $BASE/user_ca.pub\\$"; then
                  restore
                  echo "sshd 已在别处设置了 TrustedUserCAKeys，本脚本不会覆盖；已恢复原配置" >&2
                  exit 1
                fi
                (systemctl reload ssh || systemctl reload sshd || service ssh reload || service sshd reload) >/dev/null 2>&1
                echo "接入完成：项目 @PROJECT@ / 服务器 @TARGET@"
                """.replace("@PROJECT@", project).replace("@TARGET@", targetId));
        return script.toString();
    }
}
