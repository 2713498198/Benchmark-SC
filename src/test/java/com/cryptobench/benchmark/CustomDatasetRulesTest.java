package com.cryptobench.benchmark;

import com.cryptobench.benchmark.dataset.service.CustomDatasetRules;
import com.cryptobench.benchmark.execution.model.TaskDefinition;
import com.cryptobench.benchmark.execution.service.DeclarativeTaskEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CustomDatasetRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    static ObjectNode fixture(ObjectMapper mapper, String id) {
        ObjectNode pack = mapper.createObjectNode().put("schema", CustomDatasetRules.SCHEMA).put("id", id)
                .put("version", "1.0.0").put("name", "Custom test").put("algorithm", "Teaching SPN")
                .put("type", "分组密码").put("description", "Isolated task fixture");
        ObjectNode task = pack.putArray("tasks").addObject().put("id", "task-1").put("question", "Return the number of rounds.").put("category", "Structure");
        task.putObject("input").put("rounds", 4);
        task.putObject("verification").put("type", "exact-json-v1").put("expected", 4);
        return pack;
    }

    @Test
    void validatesExplicitSchemaAndProjectsOnlyPublicFields() {
        ObjectNode pack = fixture(mapper, "custom-tests");
        var validated = CustomDatasetRules.validate(pack);
        assertThat(validated.errors()).isEmpty();
        ObjectNode summary = CustomDatasetRules.summary(validated.normalized(), true, CustomDatasetRules.publicHash(validated.normalized(), true), null);
        ObjectNode catalog = CustomDatasetRules.publicCatalog(summary, validated.normalized());
        assertThat(catalog.toString()).doesNotContain("verification", "expected", "taskPackage");
        assertThat(catalog.path("tasks").get(0).path("input").path("rounds").asInt()).isEqualTo(4);
        assertThat(summary.path("executionReady").asBoolean()).isTrue();
        pack.put("unexpected", "PRIVATE_MARKER");
        assertThat(CustomDatasetRules.validate(pack).errors()).extracting(CustomDatasetRules.Issue::code).contains("unknown_field");
        assertThat(CustomDatasetRules.validate(pack).errors().toString()).doesNotContain("PRIVATE_MARKER");
    }

    @Test
    void rejectsInvalidTaskRulesDuplicateTasksReservedIdsAndVersionFormats() {
        ObjectNode pack = fixture(mapper, "mini-spn");
        pack.put("version", "01.2.0");
        ObjectNode task = (ObjectNode) pack.path("tasks").get(0);
        task.withObject("verification").put("type", "arbitrary-script").remove("expected");
        ((com.fasterxml.jackson.databind.node.ArrayNode) pack.path("tasks")).add(task.deepCopy());
        assertThat(CustomDatasetRules.validate(pack).errors()).extracting(CustomDatasetRules.Issue::code)
                .contains("reserved", "format", "unsupported", "required", "duplicate");
        pack = fixture(mapper, "custom-tests");
        pack.put("description", "x".repeat(2_000_001));
        assertThat(CustomDatasetRules.validate(pack).errors().getFirst().code()).isEqualTo("too_large");
    }

    @Test
    void exactJsonComparesTypesAndArrayOrderButNotObjectKeyOrder() throws Exception {
        ObjectNode rule = mapper.createObjectNode().put("type", "exact-json-v1");
        rule.set("expected", mapper.readTree("{\"a\":1,\"b\":[true,null]}"));
        assertThat(evaluate(rule, "{\"b\":[true,null],\"a\":1.0}").path("passed").asBoolean()).isTrue();
        assertThat(evaluate(rule, "{\"a\":\"1\",\"b\":[true,null]}").path("passed").asBoolean()).isFalse();
        assertThat(evaluate(rule, "{\"a\":1,\"b\":[null,true]}").path("passed").asBoolean()).isFalse();
        rule.putNull("expected");
        assertThat(evaluate(rule, "null").path("passed").asBoolean()).isTrue();
        var task = task(rule);
        assertThat(DeclarativeTaskEvaluator.evaluate(task, mapper.createObjectNode().put("status", "completed")).path("status").asText()).isEqualTo("invalid");
    }

    @Test
    void numericToleranceUsesDecimalBoundaryAndDoesNotDiscloseExpected() throws Exception {
        ObjectNode rule = (ObjectNode) mapper.readTree("{\"type\":\"numeric-v1\",\"expected\":100,\"absoluteTolerance\":0.01,\"relativeTolerance\":0.001}");
        assertThat(evaluate(rule, "100.1").path("passed").asBoolean()).isTrue();
        assertThat(evaluate(rule, "100.10001").path("passed").asBoolean()).isFalse();
        assertThat(evaluate(rule, "\"100\"").path("passed").asBoolean()).isFalse();
        assertThat(evaluate(rule, "500").toString()).doesNotContain("100", "expected");
        ObjectNode pack = fixture(mapper, "custom-tests");
        ((ObjectNode) pack.path("tasks").get(0)).set("verification", rule.put("relativeTolerance", -1));
        assertThat(CustomDatasetRules.validate(pack).errors()).extracting(CustomDatasetRules.Issue::code).contains("range");
    }

    @Test
    void hashesAreStableAcrossObjectOrderAndVersionsSortNumerically() throws Exception {
        assertThat(CustomDatasetRules.hash(mapper.readTree("{\"x\":1,\"y\":2}")))
                .isEqualTo(CustomDatasetRules.hash(mapper.readTree("{\"y\":2,\"x\":1.0}")));
        assertThat(CustomDatasetRules.compareVersions("1.10.0", "1.9.0")).isPositive();
        assertThat(CustomDatasetRules.compareVersions("10.0.0", "2.0.0")).isPositive();
        var decimalMapper=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        assertThat(CustomDatasetRules.hash(decimalMapper.readTree("1e50"))).isEqualTo(CustomDatasetRules.hash(decimalMapper.readTree("10e49")));
        assertThat(CustomDatasetRules.canonical(decimalMapper.readTree("1e50"))).isEqualTo("1E+50");
    }

    private TaskDefinition task(JsonNode verification) {
        return new TaskDefinition("task-1", "Question", null, List.of(), List.of(), List.of(), mapper.createObjectNode(), "", verification);
    }

    @Test
    void publicFingerprintNeverDependsOnHiddenAnswersOrRules() {
        ObjectNode original=fixture(mapper,"public-hash");ObjectNode changed=original.deepCopy();
        changed.withArray("tasks").get(0).withObject("verification").put("expected",999);
        assertThat(CustomDatasetRules.hash(changed)).isNotEqualTo(CustomDatasetRules.hash(original));
        assertThat(CustomDatasetRules.publicHash(changed,true)).isEqualTo(CustomDatasetRules.publicHash(original,true));
        changed.withArray("tasks").get(0).withObject("verification").put("type","numeric-v1").put("absoluteTolerance",1);
        assertThat(CustomDatasetRules.publicHash(changed,true)).isEqualTo(CustomDatasetRules.publicHash(original,true));
        changed.withArray("tasks").get(0).withObject("input").put("rounds",5);
        assertThat(CustomDatasetRules.publicHash(changed,true)).isNotEqualTo(CustomDatasetRules.publicHash(original,true));
        ObjectNode builtIn=mapper.createObjectNode();builtIn.putArray("tasks").addObject().put("id","t1").put("question","Public question").put("expectedAnswer","private-one");
        String publicHash=CustomDatasetRules.builtInPublicHash(builtIn,"builtin","1.0.0","evaluator");
        ((ObjectNode)builtIn.path("tasks").get(0)).put("expectedAnswer","private-two");
        assertThat(CustomDatasetRules.builtInPublicHash(builtIn,"builtin","1.0.0","evaluator")).isEqualTo(publicHash);
    }

    @Test
    void rejectsTaskIdsDifferingOnlyByCaseForMysqlCompatibility(){
        ObjectNode pack=fixture(mapper,"case-sensitive");ObjectNode task=(ObjectNode)pack.path("tasks").get(0);task.put("id","Task-A");
        pack.withArray("tasks").add(task.deepCopy().put("id","task-a"));
        assertThat(CustomDatasetRules.validate(pack).errors()).extracting(CustomDatasetRules.Issue::code).contains("duplicate");
    }
    private ObjectNode evaluate(ObjectNode rule, String answer) throws Exception {
        ObjectNode response = mapper.createObjectNode().put("status", "completed");
        response.putObject("artifact").set("answer", mapper.readTree(answer));
        return DeclarativeTaskEvaluator.evaluate(task(rule), response);
    }
}
