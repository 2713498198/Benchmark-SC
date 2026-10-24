package com.cryptobench.benchmark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用启动入口：启动内嵌 Web 服务，同时提供业务 API 和 resources/static 下的前端资源。
 * 排除默认用户服务自动配置，由项目自己的账号、会话过滤器完成认证。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
// 扫描配置属性，将 YAML 中的 benchmark 配置绑定为 BenchmarkProperties。
@ConfigurationPropertiesScan
public class CryptoBenchApplication {
    /**
     * 创建 Spring 容器，触发组件扫描、自动配置与应用启动。
     */
    public static void main(String[] args) {
        SpringApplication.run(CryptoBenchApplication.class, args);
    }
}
