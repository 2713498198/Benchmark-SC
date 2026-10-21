package com.cryptobench.benchmark.auth.controller;

import com.cryptobench.benchmark.auth.dto.LoginRequest;
import com.cryptobench.benchmark.auth.dto.RegisterRequest;
import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.service.AuthService;
import com.cryptobench.benchmark.auth.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final SessionService sessions;

    public AuthController(AuthService auth, SessionService sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal BenchmarkUser user) {
        return Collections.singletonMap("user", user == null ? null : user.toResponse());
    }

    @PostMapping(value = "/register", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest input,
                                      HttpServletRequest request, HttpServletResponse response) {
        return signIn(auth.register(input), request, response);
    }

    @PostMapping(value = "/login", consumes = "application/json")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest input,
                                   HttpServletRequest request, HttpServletResponse response) {
        return signIn(auth.login(input), request, response);
    }

    @PostMapping(value = "/demo", consumes = "application/json")
    public Map<String, Object> demo(@RequestBody Map<String, String> input,
                                  HttpServletRequest request, HttpServletResponse response) {
        return signIn(auth.demo(input.get("role")), request, response);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.signOut(request, response);
        return Map.of("ok", true);
    }

    @PostMapping(value = "/forgot", consumes = "application/json")
    public Map<String, Boolean> forgot(@RequestBody Map<String, Object> input) {
        // Compatibility endpoint; sending reset emails is not implemented yet.
        return Map.of("ok", true);
    }

    private Map<String, Object> signIn(BenchmarkUser user, HttpServletRequest request, HttpServletResponse response) {
        sessions.signIn(user, request, response);
        return Map.of("user", user.toResponse());
    }
}
