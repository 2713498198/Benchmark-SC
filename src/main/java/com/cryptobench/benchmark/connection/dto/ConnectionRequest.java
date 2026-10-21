package com.cryptobench.benchmark.connection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConnectionRequest(
        @NotBlank(message = "请输入连接名称") @Size(max = 50) String name,
        @NotBlank(message = "请输入服务地址") @Size(max = 2048)
        @Pattern(regexp = "(?i)^https?://[^\\s]+$", message = "服务地址必须使用 http 或 https") String url,
        @NotBlank(message = "请输入模型标识") @Size(max = 120) String model) {
}
