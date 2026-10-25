package com.cryptobench.benchmark.report.controller;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.report.service.ReportService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告 HTTP 接口：读取、保存、同步和公开；具体归属检查与状态规则委托 ReportService。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    /**
     * 游客获取公开报告，登录用户获取自己的报告。
     */
    @GetMapping
    public List<ObjectNode> list(@AuthenticationPrincipal BenchmarkUser user) {
        return reports.list(user);
    }

    /** 公开列表不随登录身份变化，供首页定时刷新使用。 */
    @GetMapping("/public")
    public List<ObjectNode> listPublished() {
        return reports.listPublished();
    }

    /**
     * 公开报告允许任何人读取；私有报告仅允许所属用户读取。
     */
    @GetMapping("/{id}")
    public ObjectNode get(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        return reports.get(user, id);
    }

    /**
     * 保存客户端导出的报告副本；客户端记录始终标记为未核验，不能伪造可信成绩。
     */
    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode save(@AuthenticationPrincipal BenchmarkUser user, @RequestBody ObjectNode input) {
        return reports.save(user, input);
    }

    /**
     * 主动标记本人的报告已同步；该操作不会自动公开报告。
     */
    @PostMapping("/{id}/sync")
    public ObjectNode sync(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        return reports.sync(user, id);
    }

    /**
     * 单独公开符合条件的本人报告，使其可供公共列表和榜单读取。
     */
    @PostMapping("/{id}/publish")
    public ObjectNode publish(@AuthenticationPrincipal BenchmarkUser user, @PathVariable String id) {
        return reports.publish(user, id);
    }
}
