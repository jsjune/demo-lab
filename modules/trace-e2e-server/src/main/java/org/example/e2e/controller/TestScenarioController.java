package org.example.e2e.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/test")
public class TestScenarioController {

    @Autowired
    private ScenarioService scenarioService;

    @GetMapping("/complex")
    public String complexFlow() throws Exception {
        return scenarioService.executeComplexFlow().get();
    }

    @GetMapping("/all")
    public String allFlow(HttpServletRequest request) throws Exception {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        return scenarioService.executeFullFlow(baseUrl);
    }

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @GetMapping("/http/success")
    public String httpSuccess(HttpServletRequest request) {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        return scenarioService.runHttpSuccess(baseUrl);
    }

    @GetMapping("/http/out-fail")
    public String httpOutFail() {
        return scenarioService.runHttpOutFail();
    }

    @GetMapping("/http/in-fail")
    public String httpInFail() {
        throw new RuntimeException("forced-http-in-failure");
    }

    @GetMapping("/mq/success")
    public String mqSuccess() throws Exception {
        return scenarioService.runMqSuccess();
    }

    @GetMapping("/mq/fail")
    public String mqFail() throws Exception {
        return scenarioService.runMqFail();
    }

    @GetMapping("/async/success")
    public String asyncSuccess() throws Exception {
        return scenarioService.runAsyncSuccess();
    }

    @GetMapping("/async/fail")
    public String asyncFail() {
        return scenarioService.runAsyncFail();
    }

    @GetMapping("/db/success")
    public String dbSuccess() {
        return scenarioService.runDbSuccess();
    }

    @GetMapping("/db/fail")
    public String dbFail() {
        return scenarioService.runDbFail();
    }

    @GetMapping("/db/fail-statement-syntax")
    public String dbFailStatementSyntax() {
        return scenarioService.runDbFailStatementSyntax();
    }

    @GetMapping("/db/fail-prepare-syntax")
    public String dbFailPrepareSyntax() {
        return scenarioService.runDbFailPrepareSyntax();
    }

    @GetMapping("/cache/success")
    public String cacheSuccess() {
        return scenarioService.runCacheSuccess();
    }

    @GetMapping("/cache/fail")
    public String cacheFail() {
        return scenarioService.runCacheFail();
    }

    @GetMapping("/error")
    public String errorFlow() {
        scenarioService.executeErrorFlow();
        return "error_done"; // Unreachable
    }
}
