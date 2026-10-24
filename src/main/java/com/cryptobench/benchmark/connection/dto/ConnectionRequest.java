package com.cryptobench.benchmark.connection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 连接元数据请求，不含 API 密钥等凭证；地址校验仅约束格式，不表示服务可达。
 * @param name 用户设置的连接名称，最多 50 个字符
 * @param url http 或 https 服务地址，最多 2048 个字符
 * @param model Agent 使用的模型标识，最多 120 个字符
 */
public record ConnectionRequest(
        @NotBlank(message = "请输入连接名称") @Size(max = 50) String name,
        @NotBlank(message = "请输入服务地址") @Size(max = 2048)
        @Pattern(regexp = "(?i)^https?://[^\\s]+$", message = "服务地址必须使用 http 或 https") String url,
        @NotBlank(message = "请输入模型标识") @Size(max = 120) String model) {
}
