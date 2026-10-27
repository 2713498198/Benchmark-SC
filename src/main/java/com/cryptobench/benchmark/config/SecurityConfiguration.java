package com.cryptobench.benchmark.config;

import com.cryptobench.benchmark.auth.security.SessionAuthenticationFilter;
import com.cryptobench.benchmark.auth.service.SessionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 配置 API 认证、角色授权、CSRF 与统一安全错误响应；静态页面由同一应用提供。
 */
@Configuration
public class SecurityConfiguration {
    /**
     * 建立安全过滤器链；使用自定义 Cookie 会话，关闭默认表单登录、Basic 和 HttpSession。
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionService sessions,
                                           BenchmarkProperties properties) throws Exception {
        // CSRF Cookie 可由脚本读取；身份认证使用的 cb_session Cookie 仍保持 HttpOnly。
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(properties.secureCookie()));
        // STATELESS 表示不创建 Spring 的 HttpSession，自定义数据库会话仍会在每个请求中验证。
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new SessionAuthenticationFilter(sessions), UsernamePasswordAuthenticationFilter.class)
                // 授权规则按顺序匹配；报告 GET 允许进入业务层，再由 Service 区分公开记录与私有记录。
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/bootstrap").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/pricing/active").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/datasets/custom").hasRole("ADMIN")
                        .requestMatchers("/api/connections", "/api/connections/**").authenticated()
                        .requestMatchers("/api/agent/**", "/api/runs/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/reports", "/api/reports/**").permitAll()
                        .requestMatchers("/api/reports", "/api/reports/**").authenticated()
                        // 未声明的 API 默认拒绝；同时阻止访问旧运行数据和环境文件路径。
                        .requestMatchers("/api/**").denyAll()
                        .requestMatchers("/data/runtime", "/data/runtime/**", "/.env", "/.env.*").denyAll()
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                error(response, 401, "请先登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                error(response, 403, "无权访问或 CSRF 令牌无效")));
        return http.build();
    }

    /**
     * 在过滤器阶段直接返回 JSON 错误；此时请求尚未进入 Controller 异常处理器。
     */
    private static void error(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
