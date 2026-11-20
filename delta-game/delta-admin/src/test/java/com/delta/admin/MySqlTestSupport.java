package com.delta.admin;

/** Shared connection settings for opt-in, isolated MySQL golden-path tests. */
public final class MySqlTestSupport {
    private MySqlTestSupport() {
    }

    public static boolean enabled(String environmentVariable) {
        return "1".equals(System.getenv(environmentVariable));
    }

    public static String jdbcUrl(String database) {
        return "jdbc:mysql://" + env("TEST_MYSQL_HOST", "127.0.0.1") + ":"
                + env("TEST_MYSQL_PORT", "17427") + "/" + database
                + "?allowPublicKeyRetrieval=true&sslMode=DISABLED&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    }

    public static String username() {
        return env("TEST_MYSQL_USER", "root");
    }

    public static String password() {
        return env("TEST_MYSQL_PASSWORD", "");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
