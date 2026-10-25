package com.cryptobench.benchmark.auth.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.service.AdminAccountService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/admin-invitations")
public class AdminInvitationController {
    private final AdminAccountService accounts;

    public AdminInvitationController(AdminAccountService accounts) { this.accounts = accounts; }

    @PostMapping(value = "/accept", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> accept(@Valid @RequestBody AcceptRequest input) {
        BenchmarkUser user = accounts.acceptInvitation(input.token(), input.password());
        return Map.of("user", user.toResponse());
    }

    public record AcceptRequest(@NotBlank String token, @NotBlank @Size(min = 12, max = 128) String password) {}
}
