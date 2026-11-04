package com.linearlite.server.sshca;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

/** OpenSSH 公钥文本的解析、规范化与指纹；只接受单行 "类型 Base64" 形式。 */
public final class SshKeys {

    private static final Set<String> HOST_KEY_TYPES = Set.of(
            "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521");

    private SshKeys() {
    }

    /** normalized 为去掉注释后的 "类型 Base64"；fingerprint 与 ssh-keygen -l 的 SHA256 格式一致。 */
    public record PublicKey(String type, String normalized, String fingerprint, byte[] blob) {
    }

    public static PublicKey parseHostKey(String line) {
        PublicKey key = parse(line);
        if (!HOST_KEY_TYPES.contains(key.type())) {
            throw new IllegalArgumentException("不支持的主机公钥类型: " + key.type());
        }
        return key;
    }

    /** 临时用户公钥只接受 Ed25519，返回 32 字节原始公钥。 */
    public static byte[] parseEd25519(String line) {
        PublicKey key = parse(line);
        if (!"ssh-ed25519".equals(key.type())) {
            throw new IllegalArgumentException("只支持 Ed25519 公钥");
        }
        byte[][] fields = readStrings(key.blob(), 2);
        if (fields[1].length != 32) {
            throw new IllegalArgumentException("Ed25519 公钥长度无效");
        }
        return fields[1];
    }

    public static String formatEd25519(byte[] publicKey) {
        byte[] blob = blob("ssh-ed25519", publicKey);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob);
    }

    public static byte[] blob(String type, byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, type.getBytes(StandardCharsets.US_ASCII));
        for (byte[] field : fields) {
            writeString(out, field);
        }
        return out.toByteArray();
    }

    public static String fingerprint(byte[] blob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void writeString(ByteArrayOutputStream out, byte[] value) {
        out.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
        out.writeBytes(value);
    }

    private static PublicKey parse(String line) {
        String trimmed = line == null ? null : line.trim();
        if (trimmed == null || trimmed.length() > 16384 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("公钥必须是单行 OpenSSH 公钥文本");
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException("公钥格式无效");
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("公钥 Base64 无效");
        }
        byte[][] head = readStrings(blob, 1);
        if (!parts[0].equals(new String(head[0], StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("公钥类型与内容不一致");
        }
        return new PublicKey(parts[0], parts[0] + " " + parts[1], fingerprint(blob), blob);
    }

    /** 读取前 count 个 SSH string 字段；数据不足或越界时抛出参数错误。 */
    private static byte[][] readStrings(byte[] blob, int count) {
        ByteBuffer buffer = ByteBuffer.wrap(blob);
        byte[][] result = new byte[count][];
        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 4) {
                throw new IllegalArgumentException("公钥内容不完整");
            }
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                throw new IllegalArgumentException("公钥内容不完整");
            }
            result[i] = new byte[length];
            buffer.get(result[i]);
        }
        return result;
    }
}
