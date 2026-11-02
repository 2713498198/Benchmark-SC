package com.cryptobench.benchmark.dataset.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** The public projection is an allowlist, never a private package with a few fields removed. */
public final class CustomDatasetRules {
    public static final String SCHEMA = "cryptobench-custom-dataset-v1";
    public static final String EVALUATOR = "declarative-evaluator-1.0.0";
    public static final int MAX_BYTES = 2_000_000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> RESERVED = Set.of("mini-spn", "key-recovery", "linear-analysis", "cipher-modeling", "cryptobench-sc-v4");
    private static final Set<String> TOP_FIELDS = Set.of("schema", "id", "name", "type", "algorithm", "version", "difficulty", "description", "tasks");
    private static final Set<String> TASK_FIELDS = Set.of("id", "question", "input", "category", "requiredTools", "requiredCapabilities", "verification");
    private CustomDatasetRules() {}

    public record Issue(String path, String code, String message) {}
    public record Validation(ObjectNode normalized, List<Issue> errors) {
        public boolean valid() { return errors.isEmpty(); }
    }

    public static Validation validate(ObjectNode input) {
        List<Issue> errors = new ArrayList<>();
        if (input == null) return new Validation(null, List.of(new Issue("$", "required", "任务包必须是 JSON 对象")));
        if (input.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return new Validation(null, List.of(new Issue("$", "too_large", "任务包不得超过 2,000,000 字节")));
        }
        unknown(input, TOP_FIELDS, "$", errors);
        ObjectNode clean = JSON.createObjectNode();
        if (!SCHEMA.equals(input.path("schema").asText())) issue(errors, "schema", "schema", "请使用 cryptobench-custom-dataset-v1 格式");
        clean.put("schema", SCHEMA);
        String id = text(input, clean, "id", 61, true, errors, "");
        if (!id.matches("[a-z][a-z0-9-]{1,60}")) issue(errors, "id", "format", "ID 需以小写字母开头，仅含小写字母、数字和连字符，长度为 2 至 61");
        if (RESERVED.contains(id)) issue(errors, "id", "reserved", "此 ID 保留给内置题库");
        String version = text(input, clean, "version", 40, true, errors, "");
        if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) issue(errors, "version", "format", "版本需为无前导零的主版本.次版本.修订号");
        text(input, clean, "name", 120, true, errors, "");
        text(input, clean, "algorithm", 120, true, errors, "");
        text(input, clean, "description", 4000, true, errors, "");
        text(input, clean, "difficulty", 80, false, errors, "");
        if (!"分组密码".equals(input.path("type").asText())) issue(errors, "type", "unsupported", "当前支持分组密码题库");
        clean.put("type", "分组密码");
        ArrayNode tasks = clean.putArray("tasks");
        JsonNode sourceTasks = input.path("tasks");
        if (!sourceTasks.isArray() || sourceTasks.isEmpty() || sourceTasks.size() > 1000) {
            issue(errors, "tasks", "size", "tasks 必须包含 1 至 1000 个任务");
        } else {
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < sourceTasks.size(); index++) {
                JsonNode task = sourceTasks.get(index);
                String path = "tasks[" + index + "].";
                if (!task.isObject()) { issue(errors, path, "type", "任务必须是对象"); continue; }
                unknown(task, TASK_FIELDS, path, errors);
                ObjectNode item = tasks.addObject();
                String taskId = text(task, item, "id", 120, true, errors, path);
                if (!taskId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) issue(errors, path + "id", "format", "任务 ID 仅允许字母、数字、点、下划线和连字符");
                if (!ids.add(taskId.toLowerCase(java.util.Locale.ROOT))) issue(errors, path + "id", "duplicate", "任务 ID 重复（不区分大小写）");
                text(task, item, "question", 20000, true, errors, path);
                text(task, item, "category", 80, false, errors, path);
                if (!task.path("input").isObject()) issue(errors, path + "input", "type", "input 必须是公开 JSON 对象");
                else if (!boundedJson(task.path("input"), 0)) issue(errors, path + "input", "depth", "公开输入嵌套不得超过 32 层，数字必须有限");
                else item.set("input", task.path("input").deepCopy());
                strings(task, item, "requiredTools", path, errors);
                strings(task, item, "requiredCapabilities", path, errors);
                JsonNode verification = task.path("verification");
                if (!verification.isObject()) { issue(errors, path + "verification", "required", "每个任务必须提供私有评分规则"); continue; }
                unknown(verification, Set.of("type", "expected", "absoluteTolerance", "relativeTolerance"), path + "verification.", errors);
                String rule = verification.path("type").asText();
                if (!Set.of("exact-json-v1", "numeric-v1").contains(rule)) issue(errors, path + "verification.type", "unsupported", "仅支持 exact-json-v1 和 numeric-v1");
                if (!verification.has("expected")) issue(errors, path + "verification.expected", "required", "缺少标准答案");
                else if (!boundedJson(verification.get("expected"), 0)) issue(errors, path + "verification.expected", "invalid", "标准答案结构无效");
                ObjectNode privateRule = item.putObject("verification");
                privateRule.put("type", rule);
                if (verification.has("expected")) privateRule.set("expected", verification.get("expected").deepCopy());
                if ("numeric-v1".equals(rule)) {
                    if (!verification.path("expected").isNumber() || !Double.isFinite(verification.path("expected").asDouble())) issue(errors, path + "verification.expected", "type", "数值规则的标准答案必须是有限数字");
                    for (String tolerance : List.of("absoluteTolerance", "relativeTolerance")) {
                        JsonNode number = verification.get(tolerance);
                        if (number != null && (!number.isNumber() || !Double.isFinite(number.asDouble()) || number.decimalValue().signum() < 0)) issue(errors, path + "verification." + tolerance, "range", "误差必须为非负有限数字");
                        else if (number != null) privateRule.set(tolerance, number.deepCopy());
                        else privateRule.put(tolerance, 0);
                    }
                } else if (verification.has("absoluteTolerance") || verification.has("relativeTolerance")) {
                    issue(errors, path + "verification", "unsupported", "精确匹配规则不接受数值误差参数");
                }
            }
        }
        return new Validation(clean, List.copyOf(errors));
    }

    public static ObjectNode summary(ObjectNode source, boolean executable, String hash, String publishedAt) {
        ObjectNode result = JSON.createObjectNode();
        for (String field : List.of("id", "version", "name", "type", "algorithm", "difficulty", "description")) {
            if (source.path(field).isTextual()) result.put(field, source.path(field).asText());
        }
        result.put("schema", executable ? SCHEMA : "legacy");
        result.put("custom", true);
        result.put("published", true);
        result.put("demo", false);
        result.put("executionReady", executable);
        result.put("executionStatus", executable ? "ready" : "legacy_unbound");
        result.put("tasks", source.path("tasks").isArray() ? source.path("tasks").size()
                : source.path("taskPackage").isArray() ? source.path("taskPackage").size() : source.path("tasks").asInt(0));
        result.put("icon", "grid");
        result.put("color", "blue");
        result.put("subtitle", "CUSTOM DATASET");
        result.putArray("tags").add("自定义").add("固定版本");
        if (executable) result.put("evaluatorVersion", EVALUATOR);
        if (hash != null) result.put("catalogSha256", hash);
        if (publishedAt != null) result.put("publishedAt", publishedAt);
        result.put("catalogUrl", "/api/datasets/" + org.springframework.web.util.UriUtils.encodePathSegment(source.path("id").asText(), StandardCharsets.UTF_8)
                + "/versions/" + org.springframework.web.util.UriUtils.encodePathSegment(source.path("version").asText(), StandardCharsets.UTF_8) + "/catalog");
        return result;
    }

    public static ObjectNode publicCatalog(ObjectNode metadata, ObjectNode privatePackage) {
        ObjectNode result = JSON.createObjectNode();
        result.set("dataset", metadata.deepCopy());
        ArrayNode tasks = result.putArray("tasks");
        JsonNode source = privatePackage.path("tasks").isArray() ? privatePackage.path("tasks") : privatePackage.path("taskPackage");
        if (source.isArray()) for (JsonNode task : source) {
            if (!task.isObject()) continue;
            ObjectNode item = tasks.addObject();
            for (String field : List.of("id", "question", "category")) if (task.path(field).isTextual()) item.put(field, task.path(field).asText());
            if (!item.has("question") && task.path("objective").isTextual()) item.put("question", task.path("objective").asText());
            if (task.path("input").isObject()) item.set("input", task.path("input").deepCopy());
            for (String field : List.of("requiredTools", "requiredCapabilities")) {
                ArrayNode values = item.putArray(field);
                if (task.path(field).isArray()) task.path(field).forEach(value -> { if (value.isTextual()) values.add(value.asText()); });
            }
        }
        return result;
    }

    public static String hash(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    /** Public fingerprint covers only explicitly public content, never answers or private rules. */
    public static String publicHash(ObjectNode privatePackage, boolean executable) {
        return hash(publicCatalog(summary(privatePackage, executable, null, null), privatePackage));
    }

    public static String builtInPublicHash(JsonNode privateCatalog, String datasetId, String version, String evaluatorVersion) {
        ObjectNode result = JSON.createObjectNode();
        result.putObject("dataset").put("id", datasetId).put("version", version).put("evaluatorVersion", evaluatorVersion);
        ArrayNode tasks = result.putArray("tasks");
        for (JsonNode task : privateCatalog.path("tasks")) {
            ObjectNode item = tasks.addObject();
            for (String field : List.of("id", "question", "input", "category", "publicFiles", "requiredTools", "requiredCapabilities")) {
                if (task.has(field)) item.set(field, task.get(field).deepCopy());
            }
        }
        return hash(result);
    }

    public static String canonical(JsonNode value) {
        if (value.isObject()) {
            List<String> names = new ArrayList<>(); value.fieldNames().forEachRemaining(names::add); names.sort(String::compareTo);
            return "{" + names.stream().map(key -> JSON.getNodeFactory().textNode(key).toString() + ":" + canonical(value.get(key))).collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        if (value.isArray()) { List<String> items = new ArrayList<>(); value.forEach(item -> items.add(canonical(item))); return "[" + String.join(",", items) + "]"; }
        if (value.isNumber()) return value.decimalValue().stripTrailingZeros().toString();
        return value.toString();
    }

    public static boolean supportedJson(JsonNode value) { return value != null && boundedJson(value, 0); }

    public static int compareVersions(String left, String right) {
        if (left.matches("[0-9]+\\.[0-9]+\\.[0-9]+") && right.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            String[] a = left.split("\\."), b = right.split("\\.");
            for (int index = 0; index < 3; index++) { int comparison = new BigInteger(a[index]).compareTo(new BigInteger(b[index])); if (comparison != 0) return comparison; }
        }
        return left.compareTo(right);
    }

    private static boolean boundedJson(JsonNode value, int depth) {
        if (depth > 32 || value.isNumber() && !Double.isFinite(value.asDouble())) return false;
        if (value.isContainerNode()) for (JsonNode item : value) if (!boundedJson(item, depth + 1)) return false;
        return true;
    }
    private static String text(JsonNode source, ObjectNode target, String field, int limit, boolean required, List<Issue> errors, String prefix) {
        JsonNode value = source.get(field);
        if (value == null && !required) return "";
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty() || value.asText().length() > limit) {
            issue(errors, prefix + field, "text", "字段必须是长度不超过 " + limit + " 的非空文本"); return "";
        }
        String clean = value.asText().trim(); target.put(field, clean); return clean;
    }
    private static void strings(JsonNode source, ObjectNode target, String field, String prefix, List<Issue> errors) {
        ArrayNode result = target.putArray(field); JsonNode values = source.get(field);
        if (values == null) return;
        if (!values.isArray() || values.size() > 50) { issue(errors, prefix + field, "type", "能力列表必须是至多 50 项的文本数组"); return; }
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 120 || !unique.add(value.asText().trim())) issue(errors, prefix + field, "value", "能力名称需非空、不重复且不超过 120 字符");
            else result.add(value.asText().trim());
        }
    }
    private static void unknown(JsonNode source, Set<String> allowed, String path, List<Issue> errors) {
        source.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) issue(errors, path, "unknown_field", "存在不支持的字段，请使用当前任务包模板"); });
    }
    private static void issue(List<Issue> errors, String path, String code, String message) {
        if (errors.size() < 100) errors.add(new Issue(path, code, message));
    }
}
