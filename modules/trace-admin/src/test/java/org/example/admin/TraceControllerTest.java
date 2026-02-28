package org.example.admin;

import org.example.common.TraceCategory;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TraceController.class)
@DisplayName("컨트롤러: TraceController (관리자 UI)")
class TraceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TraceService traceService;

    @Test
    @DisplayName("트랜잭션 상세 조회 시 에러 정보가 포함된 이벤트들이 모델에 담겨야 한다")
    void testTraceDetailWithError() throws Exception {
        String txId = "error-tx-123";
        TraceEvent errorEvent = new TraceEvent(
            "e1", txId, TraceEventType.HTTP_IN_END, TraceCategory.HTTP,
            "server-1", "GET /api/test", 100L, false, System.currentTimeMillis(),
            Map.of("errorMessage", "Connection timeout", "errorType", "RuntimeException")
        );

        given(traceService.getTraceDetail(txId)).willReturn(List.of(errorEvent));

        mockMvc.perform(get("/trace/" + txId))
            .andExpect(status().isOk())
            .andExpect(view().name("detail"))
            .andExpect(model().attributeExists("events"))
            .andExpect(model().attribute("txId", txId));
    }
}
