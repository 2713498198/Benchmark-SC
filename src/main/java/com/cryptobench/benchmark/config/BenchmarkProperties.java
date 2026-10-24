package com.cryptobench.benchmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 application*.yml 中 benchmark 前缀的业务配置；由启动入口扫描注册。
 * @param demoEnabled 是否开放体验账号入口
 * @param secureCookie 是否只通过 HTTPS 发送会话及 CSRF Cookie
 * @param legacyImportEnabled 是否在启动时显式启用旧数据导入
 * @param legacyDbFile 旧版 JSON 数据文件路径，相对路径基于进程工作目录
 */
@ConfigurationProperties(prefix = "benchmark")
public record BenchmarkProperties(boolean demoEnabled, boolean secureCookie,
                                  boolean legacyImportEnabled, String legacyDbFile) {
}
