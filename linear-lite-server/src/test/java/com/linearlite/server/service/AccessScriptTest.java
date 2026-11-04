package com.linearlite.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessScriptTest {

    private static final String CA_1 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICAONE linear-lite ENG";
    private static final String CA_2 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICATWO linear-lite ENG";

    @Test
    void scriptIsValidShellAndListsOnlyThisServersPrincipals(@TempDir Path dir) throws Exception {
        String script = AccessScript.render("ENG", "prod-api", List.of(CA_1, CA_2), List.of("deploy", "root"));
        Path file = dir.resolve("access.sh");
        Files.writeString(file, script);

        Process syntax = new ProcessBuilder("sh", "-n", file.toString()).redirectErrorStream(true).start();
        String output = new String(syntax.getInputStream().readAllBytes());
        assertTrue(syntax.waitFor(10, TimeUnit.SECONDS), "sh -n timed out");
        assertEquals(0, syntax.exitValue(), output);

        assertTrue(script.contains(CA_1) && script.contains(CA_2));
        assertTrue(script.contains("'rt.ENG.prod-api.deploy'"));
        assertTrue(script.contains("'rt.ENG.prod-api.root'"));
        assertFalse(script.contains("rt.ENG.other"));
        // CA 公钥放在带引号的 heredoc 中，不会被 shell 展开。
        assertTrue(script.contains("<<'LINEAR_LITE_CA'"));
        // sshd -t 失败或他处已设置 TrustedUserCAKeys 时回滚，不留下半成品配置。
        assertTrue(script.contains("sshd -t"));
        assertTrue(script.contains("restore"));
    }

    @Test
    void scriptRunsAgainstAnIsolatedRootAndSummarisesPrincipalsPerAccount(@TempDir Path dir) throws Exception {
        // 把 /etc/ssh 换成临时目录，验证汇总逻辑：两台服务器共用一个账号时 principals 文件包含两行。
        Path base = dir.resolve("linear-lite");
        String first = AccessScript.render("ENG", "prod-api", List.of(CA_1), List.of("deploy"));
        String second = AccessScript.render("OPS", "prod-db", List.of(CA_2), List.of("deploy", "root"));
        for (String script : List.of(first, second)) {
            String isolated = script.replace("/etc/ssh/linear-lite", base.toString())
                    .replaceAll("(?m)^# 4\\. sshd 配置.*$", "exit 0 # 4.");
            Path file = Files.createTempFile(dir, "script", ".sh");
            Files.writeString(file, isolated.replace("[ \"$(id -u)\" -eq 0 ] || { echo \"请使用 root 执行\" >&2; exit 1; }", ":"));
            Process process = new ProcessBuilder("sh", file.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), output);
        }
        assertEquals(CA_1 + "\n" + CA_2 + "\n", Files.readString(base.resolve("user_ca.pub")));
        List<String> deploy = Files.readAllLines(base.resolve("principals/deploy"));
        assertEquals(List.of("rt.ENG.prod-api.deploy", "rt.OPS.prod-db.deploy"), deploy);
        assertEquals(List.of("rt.OPS.prod-db.root"), Files.readAllLines(base.resolve("principals/root")));
    }
}
