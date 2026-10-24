package com.cryptobench.benchmark.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求参数；由 Controller 的 @Valid 触发非空、邮箱格式和长度校验。
 * @param email 登录邮箱，业务层会去除首尾空白并转为小写
 * @param password 待验证的原始密码，仅用于本次认证
 */
public record LoginRequest(
        @NotBlank(message = "请输入邮箱") @Email(message = "请输入有效邮箱") @Size(max = 254) String email,
        @NotBlank(message = "请输入密码") @Size(max = 128, message = "密码长度不能超过 128 位") String password) {
}
