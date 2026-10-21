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

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionService sessions,
                                           BenchmarkProperties properties) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(properties.secureCookie()));
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new SessionAuthenticationFilter(sessions), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/bootstrap").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/datasets/custom").hasRole("ADMIN")
                        .requestMatchers("/api/connections", "/api/connections/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/reports", "/api/reports/**").permitAll()
                        .requestMatchers("/api/reports", "/api/reports/**").authenticated()
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

    private static void error(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
