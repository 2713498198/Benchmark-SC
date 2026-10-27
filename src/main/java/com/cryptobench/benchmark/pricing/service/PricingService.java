package com.cryptobench.benchmark.pricing.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.config.PricingProperties;
import com.cryptobench.benchmark.execution.service.ServerCostCalculator;
import com.cryptobench.benchmark.pricing.repository.PricingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {
    private final PricingRepository pricing;
    private final PricingProperties defaults;
    private final ServerCostCalculator calculator;

    public PricingService(PricingRepository pricing, PricingProperties defaults, ServerCostCalculator calculator) {
        this.pricing = pricing;
        this.defaults = defaults;
        this.calculator = calculator;
    }

    public List<PriceTableView> list() {
        List<PriceTableView> result = new ArrayList<>();
        for (PricingRepository.PriceTable table : pricing.tables()) {
            result.add(new PriceTableView(table.version(), table.status(), table.createdBy(), table.createdAt(),
                    table.activatedAt(), pricing.prices(table.version())));
        }
        return result;
    }

    public PriceTableView active() {
        // The public snapshot must not reveal the administrator who published a price table.
        return pricing.active().map(table -> new PriceTableView(table.version(), table.status(), null,
                table.createdAt(), table.activatedAt(), pricing.prices(table.version()))).orElseGet(() -> {
                    List<PricingRepository.ModelPrice> rows = defaults.getModels().entrySet().stream()
                            .map(e -> new PricingRepository.ModelPrice(defaults.getVersion(), normalize(e.getKey()),
                                    value(e.getValue().getInputPerMillionTokens()), value(e.getValue().getOutputPerMillionTokens()), "USD"))
                            .toList();
                    return new PriceTableView(defaults.getVersion(), "config", null, null, null, rows);
                });
    }

    @Transactional
    public PriceTableView create(BenchmarkUser actor, String version, List<ModelPriceInput> inputs) {
        String cleanVersion = version == null ? "" : version.trim();
        if (!cleanVersion.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{2,119}")) throw bad("价格表版本无效");
        if (pricing.exists(cleanVersion)) throw new ApiException(HttpStatus.CONFLICT, "价格表版本已存在");
        if (inputs == null || inputs.isEmpty()) throw bad("价格表至少需要一个模型");
        java.util.HashSet<String> models = new java.util.HashSet<>();
        pricing.insertTable(cleanVersion, "draft", actor.id(), Instant.now().toString());
        for (ModelPriceInput input : inputs) {
            if (input == null) throw bad("价格项不能为空");
            String model = normalize(input.model());
            if (model.isBlank() || !models.add(model)) throw bad("模型名称重复或为空");
            double in = finite(input.inputPerMillionTokens()), out = finite(input.outputPerMillionTokens());
            String currency = input.currency() == null ? "USD" : input.currency().trim().toUpperCase(Locale.ROOT);
            if (!currency.matches("[A-Z]{3}")) throw bad("货币必须是三位 ISO 代码");
            pricing.insertPrice(cleanVersion, model, in, out, currency);
        }
        return list().stream().filter(item -> item.version().equals(cleanVersion)).findFirst().orElseThrow();
    }

    @Transactional
    public PriceTableView activate(String version) {
        if (version == null || pricing.prices(version.trim()).isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "价格表不存在或为空");
        if (pricing.activate(version.trim(), Instant.now().toString()) != 1) throw new ApiException(HttpStatus.NOT_FOUND, "价格表不存在");
        calculator.reload();
        return active();
    }

    private static String normalize(String model) { return model == null ? "" : model.trim().toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' '); }
    private static double value(Double value) { return value == null ? 0 : value; }
    private static double finite(Double value) { if (value == null || !Double.isFinite(value) || value < 0) throw bad("价格必须是非负有限数字"); return value; }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }

    public record ModelPriceInput(String model, Double inputPerMillionTokens, Double outputPerMillionTokens, String currency) {}
    public record PriceTableView(String version, String status, String createdBy, String createdAt, String activatedAt,
                                 List<PricingRepository.ModelPrice> prices) {}
}
