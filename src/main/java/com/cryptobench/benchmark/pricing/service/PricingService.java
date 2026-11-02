package com.cryptobench.benchmark.pricing.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.AdminAuditRepository;
import com.cryptobench.benchmark.common.ApiException;
import com.cryptobench.benchmark.config.PricingProperties;
import com.cryptobench.benchmark.execution.service.ServerCostCalculator;
import com.cryptobench.benchmark.pricing.repository.PricingRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {
    private final PricingRepository pricing;
    private final ServerCostCalculator calculator;
    private final AdminAuditRepository audits;
    public PricingService(PricingRepository pricing, PricingProperties defaults, ServerCostCalculator calculator) {
        this(pricing, defaults, calculator, null);
    }
    @Autowired
    public PricingService(PricingRepository pricing, PricingProperties defaults, ServerCostCalculator calculator, AdminAuditRepository audits) {
        this.pricing = pricing; this.calculator = calculator; this.audits = audits;
    }

    public List<PriceTableView> list() {
        long activeRevision = pricing.state(false).revision();
        return pricing.tables().stream().map(t -> view(t, activeRevision, true)).toList();
    }

    public PriceTableView active() {
        var snapshot = calculator.snapshot();
        String version = snapshot.path("version").asText();
        List<PricingRepository.ModelPrice> rows = new ArrayList<>();
        snapshot.path("prices").forEach(p -> rows.add(new PricingRepository.ModelPrice(version, p.path("model").asText(),
                new BigDecimal(p.path("inputPerMillionTokens").asText()), new BigDecimal(p.path("outputPerMillionTokens").asText()), p.path("currency").asText())));
        var table = "database".equals(snapshot.path("source").asText()) ? pricing.table(version, false)
                : java.util.Optional.<PricingRepository.PriceTable>empty();
        return new PriceTableView(version, table.map(PricingRepository.PriceTable::status).orElse("config"), null,
                table.map(PricingRepository.PriceTable::createdAt).orElse(null), table.map(PricingRepository.PriceTable::activatedAt).orElse(null),
                rows, table.map(PricingRepository.PriceTable::revision).orElse(0L), snapshot.path("revision").asLong(), false);
    }

    @Transactional
    public PriceTableView create(BenchmarkUser actor, String version, List<ModelPriceInput> inputs) {
        requireAdmin(actor);
        String cleanVersion = version == null ? "" : version.trim();
        if (!cleanVersion.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{2,119}")) throw bad("价格表版本需为 3 至 120 位字母、数字、点、下划线或连字符");
        List<PricingRepository.ModelPrice> rows = validate(cleanVersion, inputs);
        if (pricing.exists(cleanVersion)) throw conflict("价格表版本已存在");
        try { pricing.insertTable(cleanVersion, "draft", actor.id(), Instant.now().toString()); }
        catch (DuplicateKeyException exception) { throw conflict("价格表版本已存在"); }
        rows.forEach(p -> pricing.insertPrice(cleanVersion, p.model(), p.inputPerMillionTokens(), p.outputPerMillionTokens(), "USD"));
        audit(actor, "create_pricing", cleanVersion, rows.size(), null);
        return view(pricing.table(cleanVersion, false).orElseThrow(), pricing.state(false).revision(), true);
    }

    /** Legacy service entry point; the HTTP controller always supplies the authenticated actor. */
    @Transactional
    public PriceTableView activate(String version) { return activateInternal(null, version, pricing.state(false).revision(), null); }

    @Transactional
    public PriceTableView activate(BenchmarkUser actor, String version, Long expectedRevision, Long expectedTableRevision) {
        requireAdmin(actor);
        // Missing revision preserves old API clients. New clients always send the preview revision.
        long revision = expectedRevision == null ? pricing.state(false).revision() : expectedRevision;
        return activateInternal(actor, version, revision, expectedTableRevision);
    }

    private PriceTableView activateInternal(BenchmarkUser actor, String version, long expectedRevision, Long expectedTableRevision) {
        PricingRepository.State state = pricing.state(true);
        if (state.revision() != expectedRevision) throw conflict("当前价格表已改变，请重新查看启用差异");
        var table = pricing.table(version, true).orElseThrow(() -> missing("价格表不存在"));
        if (expectedTableRevision != null && table.revision() != expectedTableRevision) throw conflict("待启用价格表已改变，请重新查看差异");
        List<PricingRepository.ModelPrice> rows = pricing.prices(version);
        if (rows.isEmpty()) throw bad("价格表至少需要一个模型");
        if (rows.stream().anyMatch(p -> !"USD".equals(p.currency()))) throw bad("历史非 USD 价格表不能启用；请复制后填写明确的 USD 价格");
        if (!version.equals(state.activeVersion())) {
            pricing.activate(version, Instant.now().toString());
            audit(actor, "activate_pricing", version, rows.size(), state.activeVersion());
        }
        return active();
    }

    public ActivationPreview preview(String version) {
        PriceTableView current = active();
        var table = pricing.table(version, false).orElseThrow(() -> missing("价格表不存在"));
        List<PricingRepository.ModelPrice> target = pricing.prices(version);
        Map<String, PricingRepository.ModelPrice> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        current.prices().forEach(p -> before.put(p.model(), p)); target.forEach(p -> after.put(p.model(), p));
        var models = new java.util.TreeSet<>(before.keySet()); models.addAll(after.keySet());
        List<PriceChange> changes = new ArrayList<>();
        for (String model : models) {
            var a = before.get(model); var b = after.get(model);
            String kind = a == null ? "added" : b == null ? "removed"
                    : a.inputPerMillionTokens().compareTo(b.inputPerMillionTokens()) != 0
                    || a.outputPerMillionTokens().compareTo(b.outputPerMillionTokens()) != 0 || !a.currency().equals(b.currency()) ? "changed" : "unchanged";
            changes.add(new PriceChange(model, kind, a, b));
        }
        return new ActivationPreview(current.version(), version, current.activeRevision(), table.revision(), changes,
                target.stream().allMatch(p -> "USD".equals(p.currency())));
    }

    private PriceTableView view(PricingRepository.PriceTable t, long activeRevision, boolean admin) {
        return new PriceTableView(t.version(), t.status(), admin ? t.createdBy() : null, t.createdAt(), t.activatedAt(),
                pricing.prices(t.version()), t.revision(), activeRevision, false);
    }
    private static List<PricingRepository.ModelPrice> validate(String version, List<ModelPriceInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 500) throw bad("价格表需要 1 至 500 个模型");
        var models = new HashSet<String>(); var rows = new ArrayList<PricingRepository.ModelPrice>();
        for (ModelPriceInput input : inputs) {
            if (input == null) throw bad("价格项不能为空");
            String model = ServerCostCalculator.normalize(input.model());
            if (model.isBlank() || model.length() > 160 || !models.add(model)) throw bad("模型名称重复、为空或超过 160 位");
            String currency = input.currency() == null ? "USD" : input.currency().trim().toUpperCase(Locale.ROOT);
            if (!"USD".equals(currency)) throw bad("新价格表仅支持 USD，不会自动换算币种");
            rows.add(new PricingRepository.ModelPrice(version, model, decimal(input.inputPerMillionTokens()), decimal(input.outputPerMillionTokens()), "USD"));
        }
        return rows;
    }
    private static BigDecimal decimal(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 8
                || value.compareTo(new BigDecimal("999999999999.99999999")) > 0) throw bad("价格必须为非负数，最多 12 位整数和 8 位小数");
        return value.setScale(8);
    }
    private void audit(BenchmarkUser actor, String action, String version, int count, String previous) {
        if (audits == null) return;
        var metadata = JsonNodeFactory.instance.objectNode().put("version", version).put("modelCount", count);
        if (previous != null) metadata.put("previousVersion", previous);
        audits.insert("audit_" + UUID.randomUUID(), actor == null ? null : actor.id(), null, action, metadata.toString(), "success", Instant.now().toString());
    }
    private static void requireAdmin(BenchmarkUser actor) {
        if (actor == null || !"admin".equals(actor.role())) throw new ApiException(HttpStatus.FORBIDDEN, "仅管理员可以管理价格表");
    }
    private static ApiException bad(String text) { return new ApiException(HttpStatus.BAD_REQUEST, text); }
    private static ApiException conflict(String text) { return new ApiException(HttpStatus.CONFLICT, text); }
    private static ApiException missing(String text) { return new ApiException(HttpStatus.NOT_FOUND, text); }

    public record ModelPriceInput(String model, BigDecimal inputPerMillionTokens, BigDecimal outputPerMillionTokens, String currency) {
        public ModelPriceInput(String model, double input, double output, String currency) {
            this(model, BigDecimal.valueOf(input), BigDecimal.valueOf(output), currency);
        }
    }
    public record PriceTableView(String version, String status, String createdBy, String createdAt, String activatedAt,
                                 List<PricingRepository.ModelPrice> prices, long revision, long activeRevision, boolean editable) {}
    public record PriceChange(String model, String kind, PricingRepository.ModelPrice before, PricingRepository.ModelPrice after) {}
    public record ActivationPreview(String currentVersion, String targetVersion, long expectedRevision, long expectedTableRevision,
                                    List<PriceChange> changes, boolean usdOnly) {}
}
