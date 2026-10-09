package com.lbz.f1aipredict.it.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 集成测试连接参数校验。
 * <p>
 * 参数只来自隔离 MySQL 环境文件；任何缺失或不符合隔离规则的地址都直接抛异常使测试失败，
 * 绝不跳过，也不回退到其他数据源。异常信息不包含口令或连接地址。
 */
public final class ItEnvironment {

    public static final String JDBC_URL = "F1_IT_JDBC_URL";
    public static final String USERNAME = "F1_IT_DB_USERNAME";
    public static final String PASSWORD = "F1_IT_DB_PASSWORD";
    public static final String RUN_ID = "F1_IT_RUN_ID";
    public static final String REMOTE_MODE = "F1_IT_REMOTE_MODE";
    public static final String DATABASE_NAME = "F1_IT_DATABASE";
    public static final String MARKER_SCHEMA = "F1_IT_MARKER_SCHEMA";

    /** 本地 Apple container 使用的业务库及独立守护库。 */
    public static final String DATABASE = "f1_ai_predict";
    private static final String LOCAL_MARKER_SCHEMA = "f1_it_guard";

    private static final Pattern LOOPBACK_URL = Pattern.compile(
            "^jdbc:mysql://(127\\.0\\.0\\.1|localhost):(\\d{1,5})/" + DATABASE
                    + "(\\?[A-Za-z0-9=&_.-]*)?$");
    private static final Pattern REMOTE_URL = Pattern.compile(
            "^jdbc:mysql://127\\.0\\.0\\.1:(\\d{1,5})/([A-Za-z][A-Za-z0-9_]{0,63})"
                    + "\\?connectionTimeZone=UTC&characterEncoding=UTF-8&sslMode=DISABLED"
                    + "&allowPublicKeyRetrieval=true$");
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern REMOTE_DATABASE_PATTERN = Pattern.compile("^f1_it_[0-9a-f]{16}$");

    private ItEnvironment() {
    }

    /** 校验全部连接变量并返回本次测试应匹配的 run 标记。 */
    public static String requireDedicatedDatabase() {
        List<String> missing = new ArrayList<>();
        for (String name : List.of(JDBC_URL, USERNAME, PASSWORD, RUN_ID)) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("集成测试缺少连接环境变量 " + missing
                    + "；请先执行隔离 MySQL 初始化脚本并加载其连接环境文件");
        }

        String runId = System.getenv(RUN_ID);
        if (!isValidRunId(runId)) {
            throw new IllegalStateException("F1_IT_RUN_ID 格式无效");
        }
        String remoteMode = System.getenv(REMOTE_MODE);
        if (remoteMode != null && !"true".equals(remoteMode)) {
            throw new IllegalStateException("F1_IT_REMOTE_MODE 只能显式设为 true 或保持未设置");
        }
        if ("true".equals(remoteMode)) {
            if (!System.getenv(USERNAME).matches("^f1_it_[0-9a-f]{16}$")) {
                throw new IllegalStateException("远程集成测试账号格式无效");
            }
            validateRemote(System.getenv(JDBC_URL), System.getenv(DATABASE_NAME), System.getenv(MARKER_SCHEMA));
            return runId;
        }

        String url = System.getenv(JDBC_URL);
        if (!isLoopbackDedicatedUrl(url)) {
            throw new IllegalStateException("本地集成测试必须使用 127.0.0.1/localhost 上的专属库");
        }
        return runId;
    }

    static boolean isValidRunId(String runId) {
        return runId != null && RUN_ID_PATTERN.matcher(runId).matches();
    }

    static boolean isLoopbackDedicatedUrl(String url) {
        if (url == null) {
            return false;
        }
        Matcher matcher = LOOPBACK_URL.matcher(url);
        return matcher.matches() && isValidPort(matcher.group(2));
    }

    static String validateRemote(String url, String database, String markerSchema) {
        Matcher matcher = url == null ? null : REMOTE_URL.matcher(url);
        if (matcher == null || !matcher.matches() || !isValidPort(matcher.group(1))) {
            throw new IllegalStateException("远程集成测试 JDBC 地址格式无效");
        }
        String urlDatabase = matcher.group(2);
        if (DATABASE.equals(urlDatabase)
                || !REMOTE_DATABASE_PATTERN.matcher(urlDatabase).matches()
                || !urlDatabase.equals(database)
                || !urlDatabase.equals(markerSchema)) {
            throw new IllegalStateException("远程集成测试数据库与隔离标记配置不一致，拒绝启动");
        }
        return urlDatabase;
    }

    private static boolean isValidPort(String port) {
        try {
            int value = Integer.parseInt(port);
            return value >= 1 && value <= 65535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
