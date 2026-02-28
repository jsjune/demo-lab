package org.example.admin;

import lombok.RequiredArgsConstructor;
import org.example.common.TraceEvent;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/")
@RequiredArgsConstructor
public class TraceController {

    private final TraceService traceService;

    @GetMapping
    public String index(Model model, 
                        @RequestParam(required = false) String txId,
                        @RequestParam(required = false) String serverName) {
        List<TraceEvent> traces = traceService.searchTraces(txId, serverName);
        model.addAttribute("traces", traces);
        model.addAttribute("txId", txId);
        model.addAttribute("serverName", serverName);
        return "index";
    }

    @GetMapping("/trace/{txId}")
    public String detail(@PathVariable String txId, Model model) {
        List<TraceEvent> events = traceService.getTraceDetail(txId);
        model.addAttribute("txId", txId);
        model.addAttribute("events", events);
        return "detail";
    }

    @GetMapping("/service-map")
    public String serviceMap(Model model) {
        return "service-map";
    }

    @GetMapping("/api/traces")
    @ResponseBody
    public List<TraceEvent> searchApi(@RequestParam(required = false) String txId,
                                      @RequestParam(required = false) String serverName) {
        return traceService.searchTraces(txId, serverName);
    }

    @GetMapping("/api/traces/{txId}")
    @ResponseBody
    public List<TraceEvent> detailApi(@PathVariable String txId) {
        return traceService.getTraceDetail(txId);
    }

    @GetMapping("/api/service-map")
    @ResponseBody
    public List<TraceRepository.ServiceLink> serviceMapApi() {
        return traceService.getServiceLinks();
    }

    @GetMapping("/api/metrics/summary")
    @ResponseBody
    public TraceService.MetricSummary metricsSummaryApi(@RequestParam(defaultValue = "60") int minutes,
                                                        @RequestParam(required = false) String serverName) {
        return traceService.getDashboardSummary(minutes, serverName);
    }

    @GetMapping("/alerts")
    public String alerts(Model model) {
        model.addAttribute("rules", traceService.getAllAlertRules());
        return "alerts";
    }

    @PostMapping("/alerts/save")
    public String saveAlert(@ModelAttribute TraceRepository.AlertRule rule) {
        traceService.saveAlertRule(rule);
        return "redirect:/alerts";
    }

    @PostMapping("/alerts/delete/{id}")
    public String deleteAlert(@PathVariable Long id) {
        traceService.deleteAlertRule(id);
        return "redirect:/alerts";
    }
}
