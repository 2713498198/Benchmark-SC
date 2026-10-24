package com.cryptobench.benchmark.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在 JSON 反序列化前限制 API 请求体大小，优先于其他过滤器执行。
 * 同时检查声明长度和实际读取长度，最大允许 5,000,000 字节。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 5_000_000;

    /**
     * 跳过静态资源与 GET/HEAD；拒绝超限请求，并将已读取的数据包装回请求供后续使用。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")
                || "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.sendError(413);
            return;
        }
        // 最多读取上限加一字节，既可识别没有 Content-Length 的超限请求，也避免无限读取。
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            response.sendError(413);
            return;
        }
        // 原始输入流已被读取；从缓存字节创建新流，让后续 JSON 解析仍能取得完整请求体。
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                var source = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return source.read(); }
                    @Override public int read(byte[] buffer, int offset, int length) {
                        return source.read(buffer, offset, length);
                    }
                    @Override public boolean isFinished() { return source.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    // 当前仅支持同步读取 JSON，不提供 Servlet 非阻塞读取回调。
                    @Override public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("Synchronous JSON requests only");
                    }
                };
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }
}
