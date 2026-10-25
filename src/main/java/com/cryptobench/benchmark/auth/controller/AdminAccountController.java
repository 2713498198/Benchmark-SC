package com.cryptobench.benchmark.auth.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.service.AdminAccountService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminAccountController {
    private final AdminAccountService accounts;

    public AdminAccountController(AdminAccountService accounts) { this.accounts = accounts; }

    @GetMapping("/users")
    public AdminAccountService.Page<AdminAccountService.AccountView> users(
            @RequestParam(required = false) String q, @RequestParam(required = false) String role,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return accounts.list(q, role, status, page, size);
    }

    @PostMapping(value = "/invitations", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AdminAccountService.InvitationResult invite(@AuthenticationPrincipal BenchmarkUser actor,
                                                        @Valid @RequestBody InviteRequest input) {
        return accounts.invite(actor, input.email(), input.name());
    }

    @PatchMapping(value = "/users/{id}/role", consumes = "application/json")
    public AdminAccountService.AccountView role(@AuthenticationPrincipal BenchmarkUser actor,
                                                @PathVariable String id, @Valid @RequestBody RoleRequest input) {
        return accounts.changeRole(actor, id, input.role());
    }

    @PatchMapping(value = "/users/{id}/status", consumes = "application/json")
    public AdminAccountService.AccountView status(@AuthenticationPrincipal BenchmarkUser actor,
                                                  @PathVariable String id, @Valid @RequestBody StatusRequest input) {
        return accounts.changeStatus(actor, id, input.status());
    }

    @PostMapping("/users/{id}/sessions/revoke")
    public Map<String, Object> revoke(@AuthenticationPrincipal BenchmarkUser actor, @PathVariable String id) {
        return Map.of("revoked", accounts.revokeSessions(actor, id));
    }

    @GetMapping("/audit")
    public AdminAccountService.Page<AdminAccountService.AuditView> audit(
            @RequestParam(required = false) String action, @RequestParam(required = false) String target,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return accounts.audit(action, target, page, size);
    }

    public record InviteRequest(@NotBlank @Email @Size(max = 254) String email,
                                @NotBlank @Size(max = 30) String name) {}
    public record RoleRequest(@NotBlank String role) {}
    public record StatusRequest(@NotBlank String status) {}
}
