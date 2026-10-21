package com.cryptobench.benchmark.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "请输入邮箱") @Email(message = "请输入有效邮箱") @Size(max = 254) String email,
        @NotBlank(message = "请输入密码") @Size(max = 128, message = "密码长度不能超过 128 位") String password) {
}
