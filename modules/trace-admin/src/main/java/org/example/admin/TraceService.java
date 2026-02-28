package org.example.admin;

import lombok.RequiredArgsConstructor;
import org.example.common.TraceEvent;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TraceService {

    private final TraceRepository traceRepository;

    public List<TraceEvent> searchTraces(String txId, String serverName) {
        return traceRepository.findTraces(txId, serverName);
    }

    public List<TraceEvent> getTraceDetail(String txId) {
        return traceRepository.findByTxId(txId);
    }

    public List<TraceRepository.ServiceLink> getServiceLinks() {
        return traceRepository.findServiceLinks();
    }

    public List<TraceRepository.AlertRule> getAllAlertRules() {
        return traceRepository.findAllAlertRules();
    }

    public void saveAlertRule(TraceRepository.AlertRule rule) {
        traceRepository.saveAlertRule(rule);
    }

    public void deleteAlertRule(Long id) {
        traceRepository.deleteAlertRule(id);
    }
}
