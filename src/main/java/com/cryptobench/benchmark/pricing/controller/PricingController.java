package com.cryptobench.benchmark.pricing.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.pricing.service.PricingService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PricingController {
    private final PricingService pricing;
    public PricingController(PricingService pricing) { this.pricing = pricing; }

    @GetMapping("/pricing/active")
    public PricingService.PriceTableView active() { return pricing.active(); }

    @GetMapping("/admin/pricing")
    public List<PricingService.PriceTableView> list() { return pricing.list(); }

    @PostMapping("/admin/pricing")
    public PricingService.PriceTableView create(@AuthenticationPrincipal BenchmarkUser actor,
                                                @RequestBody CreateRequest input) {
        return pricing.create(actor, input.version(), input.prices());
    }

    @GetMapping("/admin/pricing/{version}/diff")
    public PricingService.ActivationPreview preview(@PathVariable String version) { return pricing.preview(version); }

    @PostMapping("/admin/pricing/{version}/activate")
    public PricingService.PriceTableView activate(@AuthenticationPrincipal BenchmarkUser actor, @PathVariable String version,
                                                 @RequestBody(required = false) ActivationRequest input) {
        return pricing.activate(actor, version, input == null ? null : input.expectedRevision(),
                input == null ? null : input.expectedTableRevision());
    }

    public record CreateRequest(String version, List<PricingService.ModelPriceInput> prices) {}
    public record ActivationRequest(Long expectedRevision, Long expectedTableRevision) {}
}
