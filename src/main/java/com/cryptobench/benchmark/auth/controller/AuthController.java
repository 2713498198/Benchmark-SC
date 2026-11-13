package com.cryptobench.benchmark.auth.controller;

import com.cryptobench.benchmark.auth.dto.LoginRequest;
import com.cryptobench.benchmark.auth.dto.RegisterRequest;
import com.cryptobench.benchmark.auth.dto.PasswordResetRequest;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.service.AuthService;
import com.cryptobench.benchmark.auth.service.SessionService;
import com.cryptobench.benchmark.auth.service.PasswordResetCodeService;
import com.cryptobench.benchmark.auth.service.PasswordResetFlowException;
import com.cryptobench.benchmark.config.BenchmarkProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号 HTTP 接口：校验请求参数，委托业务服务，并在登录成功后设置会话 Cookie。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final SessionService sessions;
    private final PasswordResetCodeService codes;
    private final BenchmarkProperties properties;
    private static final String RESET_COOKIE = "cb_reset_grant";

    public AuthController(AuthService auth, SessionService sessions, PasswordResetCodeService codes,
            BenchmarkProperties properties) {
        this.auth = auth;
        this.sessions = sessions;
        this.codes = codes;
        this.properties = properties;
    }

    /**
     * 返回写请求需要提交的 CSRF 令牌及请求头名称；访问令牌会触发其生成。
     */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
    }

    /**
     * 返回当前用户的公开字段；游客的 user 为 null，不返回密码信息。
     */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal BenchmarkUser user) {
        return Collections.singletonMap("user", user == null ? null : user.toResponse());
    }

    /**
     * 通过 Bean Validation 校验注册字段；创建研究者账号后直接登录，响应状态为 201。
     */
    @PostMapping(value = "/register", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest input,
                                      HttpServletRequest request, HttpServletResponse response) {
        return signIn(auth.register(input), request, response);
    }

    /**
     * 验证邮箱和密码，并用新会话替换当前请求携带的旧会话。
     */
    @PostMapping(value = "/login", consumes = "application/json")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest input,
                                   HttpServletRequest request, HttpServletResponse response) {
        return signIn(auth.login(input), request, response);
    }

    /**
     * 进入体验账号；是否开放该入口由服务端环境配置决定。
     */
    @PostMapping(value = "/demo", consumes = "application/json")
    public Map<String, Object> demo(@RequestBody Map<String, String> input,
                                  HttpServletRequest request, HttpServletResponse response) {
        return signIn(auth.demo(input.get("role")), request, response);
    }

    /**
     * 撤销数据库中的当前会话，同时让浏览器删除会话 Cookie。
     */
    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.signOut(request, response);
        return Map.of("ok", true);
    }

    /** 邮箱不存在时也返回相同结果，避免账号枚举；投递由可配置 notifier 处理。 */
    @PostMapping(value = "/forgot", consumes = "application/json")
    public PasswordResetCodeService.Requested forgot(@RequestBody PasswordResetRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        var result = codes.request(string(input, "email"), request.getRemoteAddr());
        resetCookie(request, response, "", 0);
        return result;
    }

    @PostMapping(value = "/reset/verify", consumes = "application/json")
    public Map<String, Boolean> verifyReset(@RequestBody PasswordResetRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        var verified = codes.verify(string(input, "challengeId"), string(input, "code"), request.getRemoteAddr());
        resetCookie(request, response, verified.token(), verified.maxAgeSeconds());
        return Map.of("ok", true);
    }

    @GetMapping("/reset/status")
    public PasswordResetCodeService.Status resetStatus(HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        var status = codes.status(resetToken(request));
        if (!status.canReset()) resetCookie(request, response, "", 0);
        return status;
    }

    @PostMapping(value = "/reset/complete", consumes = "application/json")
    public Map<String, Boolean> completeReset(@RequestBody PasswordResetRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        codes.complete(resetToken(request), string(input, "challengeId"), string(input, "password"), string(input, "confirmPassword"));
        resetCookie(request, response, "", 0);
        return Map.of("ok", true);
    }

    @ExceptionHandler(PasswordResetFlowException.class)
    public ResponseEntity<Map<String, Object>> resetError(PasswordResetFlowException error,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        if ("reset_grant_expired".equals(error.code())) resetCookie(request, response, "", 0);
        Map<String, Object> body = new LinkedHashMap<>(); body.put("error", error.getMessage()); body.put("code", error.code());
        if (error.retryAfterSeconds() > 0) {
            body.put("retryAfterSeconds", error.retryAfterSeconds());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(error.retryAfterSeconds()));
        }
        return ResponseEntity.status(error.getStatus()).body(body);
    }

    private String resetToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(cookie -> RESET_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue).filter(PasswordResetCodeService::validToken).findFirst().orElse(null);
    }
    private void resetCookie(HttpServletRequest request, HttpServletResponse response, String value, int seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(RESET_COOKIE, value).httpOnly(true)
                .secure(request.isSecure() || properties.secureCookie()).sameSite("Strict").path("/api/auth/reset")
                .maxAge(Duration.ofSeconds(seconds)).build().toString());
    }
    private static void noStore(HttpServletResponse response) { response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store"); }
    private static String string(Map<String, Object> input, String key) { return input.get(key) instanceof String value ? value : ""; }

    /**
     * 统一建立登录会话，并只返回允许前端展示的用户字段。
     */
    private Map<String, Object> signIn(BenchmarkUser user, HttpServletRequest request, HttpServletResponse response) {
        sessions.signIn(user, request, response);
        return Map.of("user", user.toResponse());
    }
}
