package com.cryptobench.benchmark.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "请输入姓名") @Size(max = 30, message = "姓名长度不能超过 30 位") String name,
        @NotBlank(message = "请输入邮箱") @Email(message = "请输入有效邮箱") @Size(max = 254) String email,
        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 128, message = "密码长度应为 6 至 128 位") String password) {
}
