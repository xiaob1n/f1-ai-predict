package com.lbz.f1aipredict.sync.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Feed 同步工具类：提供 SHA-256 哈希等通用能力。
 * 禁止实例化，全部为静态方法。
 */
public final class FeedSyncUtils {

    private FeedSyncUtils() {
        // 工具类无需实例
    }

    /**
     * 计算字符串的 SHA-256 十六进制哈希（小写，64 位）。
     *
     * @param raw 原始字符串，允许为空（空字符串也会得到确定哈希）
     * @return 64 位小写十六进制哈希
     */
    public static String sha256Hex(String raw) {
        String input = raw == null ? "" : raw;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
