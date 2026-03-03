package org.example.agent.core;

import jakarta.servlet.DispatcherType;
import org.example.common.TraceEvent;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("코어: TraceRuntime (글로벌 이벤트 핸들러)")
class TraceRuntimeTest {

    private MockedStatic<TcpSender> tcpSenderMock;

    @BeforeEach
    void setUp() {
        TxIdHolder.clear();
        SpanIdHolder.clear();
        tcpSenderMock = mockStatic(TcpSender.class);
    }

    @AfterEach
    void tearDown() {
        TxIdHolder.clear();
        SpanIdHolder.clear();
        tcpSenderMock.close();
    }

    @Test
    @DisplayName("인바운드 HTTP 호출 시 유입된 트랜잭션 ID를 최우선으로 사용해야 한다")
    void testOnHttpInStartWithIncomingTxId() {
        String incomingTxId = "upstream-12345";
        TraceRuntime.onHttpInStart("GET", "/api/test", incomingTxId, false);

        assertEquals(incomingTxId, TxIdHolder.get(), "유입된 txId를 사용해야 함");
        tcpSenderMock.verify(() -> TcpSender.send(argThat(event -> 
            event.txId().equals(incomingTxId) && event.type() == TraceEventType.HTTP_IN_START
        )));
    }

    @Test
    @DisplayName("데이터베이스 쿼리 종료 시 현재 트랜잭션 컨텍스트를 포함한 이벤트를 발행해야 한다")
    void testOnDbQueryEnd() {
        String txId = "db-tx-1";
        TxIdHolder.set(txId);
        
        TraceRuntime.onDbQueryEnd("SELECT * FROM users", 100L, "h2://mem:testdb");

        tcpSenderMock.verify(() -> TcpSender.send(argThat(event -> 
            event.txId().equals(txId) && event.type() == TraceEventType.DB_QUERY_END
        )));
    }

    @Test
    @DisplayName("설정된 임계치 이상의 파일 I/O 작업 시 이벤트를 발행해야 한다")
    void testOnFileRead() {
        String txId = "io-tx-1";
        TxIdHolder.set(txId);
        
        // AgentConfig default min-size-bytes is 1024. Let's use 2000.
        TraceRuntime.onFileRead("/tmp/test.txt", 2000L, 50L, true);

        tcpSenderMock.verify(() -> TcpSender.send(argThat(event -> 
            event.txId().equals(txId) && event.type() == TraceEventType.FILE_READ
        )));
    }

    @Test
    @DisplayName("메시지 소비가 종료되면 스레드 컨텍스트를 클리어해야 한다")
    void testMqConsumeEnd() {
        String txId = "mq-tx-1";
        TxIdHolder.set(txId);

        TraceRuntime.onMqConsumeEnd("kafka", "orders", 150L);

        tcpSenderMock.verify(() -> TcpSender.send(argThat(event ->
            event.txId().equals(txId) && event.type() == TraceEventType.MQ_CONSUME_END
        )));
        assertNull(TxIdHolder.get(), "TxId should be cleared after onMqConsumeEnd");
    }

    // -----------------------------------------------------------------------
    // safeKeyToString() 테스트 (FR-05)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("safeKeyToString: 캐시 키 안전 변환")
    class SafeKeyToStringTest {

        @Test
        @DisplayName("null 입력 시 null을 반환해야 한다")
        void nullInput_returnsNull() {
            assertNull(TraceRuntime.safeKeyToString(null));
        }

        @Test
        @DisplayName("byte[] 입력 시 UTF-8 문자열로 변환해야 한다")
        void byteArrayInput_returnsUtf8String() {
            byte[] key = "user:1001".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("user:1001", TraceRuntime.safeKeyToString(key));
        }

        @Test
        @DisplayName("빈 byte[] 입력 시 빈 문자열을 반환해야 한다")
        void emptyByteArray_returnsEmptyString() {
            assertEquals("", TraceRuntime.safeKeyToString(new byte[0]));
        }

        @Test
        @DisplayName("String 입력 시 그대로 반환해야 한다")
        void stringInput_returnsAsIs() {
            assertEquals("session:abc", TraceRuntime.safeKeyToString("session:abc"));
        }

        @Test
        @DisplayName("String 이외의 객체 입력 시 String.valueOf 결과를 반환해야 한다")
        void integerInput_returnsStringValueOf() {
            assertEquals("42", TraceRuntime.safeKeyToString(42));
        }
    }

    // -----------------------------------------------------------------------
    // isErrorDispatch() 테스트
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("isErrorDispatch: 내부 에러 디스패치 감지")
    class IsErrorDispatchTest {

        @Test
        @DisplayName("jakarta.servlet.error.request_uri 속성이 있으면 에러 디스패치로 감지해야 한다 (ErrorPageFilter 경로)")
        void jakartaErrorAttribute_returnsTrue() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute("jakarta.servlet.error.request_uri", "/api/chain");

            assertTrue(TraceRuntime.isErrorDispatch(request));
        }

        @Test
        @DisplayName("javax.servlet.error.request_uri 속성이 있으면 에러 디스패치로 감지해야 한다 (Spring Boot 2.x 하위 호환)")
        void javaxErrorAttribute_returnsTrue() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute("javax.servlet.error.request_uri", "/api/chain");

            assertTrue(TraceRuntime.isErrorDispatch(request));
        }

        @Test
        @DisplayName("DispatcherType.ERROR이면 에러 디스패치로 감지해야 한다 (Tomcat native 에러 처리 경로)")
        void errorDispatcherType_returnsTrue() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setDispatcherType(DispatcherType.ERROR);

            assertTrue(TraceRuntime.isErrorDispatch(request));
        }

        @Test
        @DisplayName("에러 속성이 없고 DispatcherType.REQUEST이면 직접 요청으로 판별해야 한다")
        void noErrorAttributes_directRequest_returnsFalse() {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
            // Default: DispatcherType.REQUEST, no error attributes

            assertFalse(TraceRuntime.isErrorDispatch(request));
        }

        @Test
        @DisplayName("getAttribute를 지원하지 않는 일반 객체는 false를 반환해야 한다 (안전 폴백)")
        void plainObject_returnsFalse() {
            assertFalse(TraceRuntime.isErrorDispatch(new Object()));
        }
    }

    // -----------------------------------------------------------------------
    // Span 계층 추적 테스트 (Phase 2)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Span 계층 추적: HTTP 루트 Span과 자식 Span")
    class SpanHierarchyTest {

        private List<TraceEvent> captured;

        @BeforeEach
        void setUp() {
            captured = new ArrayList<>();
            tcpSenderMock.when(() -> TcpSender.send(any()))
                .thenAnswer(inv -> { captured.add(inv.getArgument(0)); return null; });
        }

        @AfterEach
        void tearDown() {
            SpanIdHolder.clear();
            TxIdHolder.clear();
        }

        @Test
        @DisplayName("onHttpInStart() 호출 후 SpanIdHolder에 루트 spanId가 저장되어야 한다")
        void httpInStart_setsSpanIdHolder() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", false);

            assertNotNull(SpanIdHolder.get(), "onHttpInStart() 후 SpanIdHolder는 null이 아니어야 한다");
        }

        @Test
        @DisplayName("HTTP_IN_START 이벤트의 parentSpanId는 null이어야 한다 (루트 span)")
        void httpInStart_rootEventHasNullParentSpanId() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", false);

            assertFalse(captured.isEmpty());
            TraceEvent startEvent = captured.get(0);
            assertEquals(TraceEventType.HTTP_IN_START, startEvent.type());
            assertNull(startEvent.parentSpanId(), "루트 span의 parentSpanId는 null이어야 한다");
        }

        @Test
        @DisplayName("HTTP_IN_START와 HTTP_IN_END 이벤트는 동일한 spanId를 공유해야 한다")
        void httpInEnd_usesSameSpanIdAsStart() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", false);
            TraceRuntime.onHttpInEnd("GET", "/api/test", 200, 50L);

            assertEquals(2, captured.size());
            String startSpanId = captured.get(0).spanId();
            String endSpanId   = captured.get(1).spanId();
            assertNotNull(startSpanId);
            assertEquals(startSpanId, endSpanId,
                "START와 END 이벤트는 동일한 spanId를 가져야 한다");
        }

        @Test
        @DisplayName("HTTP_IN_END 이벤트의 parentSpanId는 null이어야 한다 (루트 span)")
        void httpInEnd_rootEventHasNullParentSpanId() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", false);
            TraceRuntime.onHttpInEnd("GET", "/api/test", 200, 50L);

            TraceEvent endEvent = captured.get(1);
            assertNull(endEvent.parentSpanId(), "루트 span END의 parentSpanId는 null이어야 한다");
        }

        @Test
        @DisplayName("onHttpInEnd() 호출 후 SpanIdHolder가 클리어되어야 한다")
        void httpInEnd_clearsSpanIdHolder() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", false);
            TraceRuntime.onHttpInEnd("GET", "/api/test", 200, 50L);

            assertNull(SpanIdHolder.get(), "onHttpInEnd() 후 SpanIdHolder는 null이어야 한다");
            assertNull(TxIdHolder.get(),   "onHttpInEnd() 후 TxIdHolder도 null이어야 한다");
        }

        @Test
        @DisplayName("HTTP 처리 중 발생한 DB 쿼리는 HTTP 루트 spanId를 parentSpanId로 가져야 한다")
        void dbQueryEnd_parentSpanIdEqualsRootSpanId() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", false);
            String rootSpanId = SpanIdHolder.get();
            assertNotNull(rootSpanId);

            TraceRuntime.onDbQueryEnd("SELECT 1", 10L, "localhost");

            TraceEvent dbEvent = captured.stream()
                .filter(e -> e.type() == TraceEventType.DB_QUERY_END)
                .findFirst()
                .orElseThrow(() -> new AssertionError("DB_QUERY_END 이벤트가 없음"));

            assertEquals(rootSpanId, dbEvent.parentSpanId(),
                "DB 이벤트의 parentSpanId는 HTTP 루트 spanId여야 한다");
        }

        @Test
        @DisplayName("DB 자식 span은 루트 spanId와 다른 고유한 spanId를 가져야 한다")
        void dbQueryEnd_childSpanHasDistinctSpanId() {
            TraceRuntime.onHttpInStart("GET", "/api/test", "tx-001", false);
            String rootSpanId = SpanIdHolder.get();

            TraceRuntime.onDbQueryEnd("SELECT 1", 10L, "localhost");

            TraceEvent dbEvent = captured.stream()
                .filter(e -> e.type() == TraceEventType.DB_QUERY_END)
                .findFirst()
                .orElseThrow();

            assertNotEquals(rootSpanId, dbEvent.spanId(),
                "DB 자식 span의 spanId는 루트 spanId와 달라야 한다");
        }

        @Test
        @DisplayName("HTTP 처리 중 발생한 외부 HTTP 호출도 루트 spanId를 parentSpanId로 가져야 한다")
        void httpOut_parentSpanIdEqualsRootSpanId() {
            TraceRuntime.onHttpInStart("POST", "/api/order", "tx-002", false);
            String rootSpanId = SpanIdHolder.get();

            TraceRuntime.onHttpOut("GET", "http://payment-svc/pay", 200, 30L);

            TraceEvent httpOutEvent = captured.stream()
                .filter(e -> e.type() == TraceEventType.HTTP_OUT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("HTTP_OUT 이벤트가 없음"));

            assertEquals(rootSpanId, httpOutEvent.parentSpanId(),
                "HTTP_OUT 이벤트의 parentSpanId는 HTTP 루트 spanId여야 한다");
        }

        @Test
        @DisplayName("MQ_CONSUME_START와 MQ_CONSUME_END는 동일한 spanId를 공유해야 한다")
        void mqConsumeEnd_usesSameSpanIdAsStart() {
            TraceRuntime.onMqConsumeStart("kafka", "orders", "tx-mq-001");
            TraceRuntime.onMqConsumeEnd("kafka", "orders", 80L);

            assertEquals(2, captured.size());
            String startSpanId = captured.get(0).spanId();
            String endSpanId   = captured.get(1).spanId();
            assertEquals(startSpanId, endSpanId,
                "MQ START와 END는 동일한 spanId를 가져야 한다");
        }

        @Test
        @DisplayName("onMqConsumeEnd() 호출 후 SpanIdHolder가 클리어되어야 한다")
        void mqConsumeEnd_clearsSpanIdHolder() {
            TraceRuntime.onMqConsumeStart("kafka", "orders", "tx-mq-001");
            TraceRuntime.onMqConsumeEnd("kafka", "orders", 80L);

            assertNull(SpanIdHolder.get(), "onMqConsumeEnd() 후 SpanIdHolder는 null이어야 한다");
        }

        @Test
        @DisplayName("활성 루트 span 없이 DB 쿼리가 발생하면 parentSpanId는 null이어야 한다")
        void noRootSpan_dbQueryEnd_parentSpanIdIsNull() {
            TxIdHolder.set("tx-isolated");
            // SpanIdHolder is NOT set — simulates event outside HTTP/MQ context

            TraceRuntime.onDbQueryEnd("SELECT 1", 5L, "localhost");

            assertFalse(captured.isEmpty());
            TraceEvent dbEvent = captured.get(0);
            assertNull(dbEvent.parentSpanId(),
                "루트 span 없이 발생한 DB 이벤트의 parentSpanId는 null이어야 한다");
        }
    }
}
