package com.cryptobench.benchmark.auth.security;

import com.cryptobench.benchmark.auth.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在每次 API 请求中恢复数据库会话，并将当前用户和角色写入 Spring Security 上下文。
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final SessionService sessions;

    public SessionAuthenticationFilter(SessionService sessions) {
        this.sessions = sessions;
    }

    /**
     * 静态资源不需要查询会话数据库，仅对 /api/ 路径执行认证恢复。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    /**
     * 有效会话转为已认证身份；无会话时继续过滤器链，由后续访问规则决定是否允许请求。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        sessions.currentUser(request).ifPresent(user -> {
            // 将业务角色 admin/researcher 转为 ROLE_ADMIN/ROLE_RESEARCHER，配合 hasRole 使用。
            var authentication = new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role().toUpperCase(java.util.Locale.ROOT))));
            // 为当前请求创建上下文，Controller 可通过 @AuthenticationPrincipal 获取用户。
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        });
        chain.doFilter(request, response);
    }
}
