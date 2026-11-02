package com.cryptobench.benchmark.dataset.service;

import java.util.List;

public class DatasetValidationException extends RuntimeException {
    private final List<CustomDatasetRules.Issue> errors;
    public DatasetValidationException(List<CustomDatasetRules.Issue> errors) {
        super("任务包校验失败，请检查标出的字段");
        this.errors = List.copyOf(errors);
    }
    public List<CustomDatasetRules.Issue> errors() { return errors; }
}
