package org.example.agent.core;

import jakarta.servlet.DispatcherType;
import org.example.common.TraceEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("코어: TraceRuntime (글로벌 이벤트 핸들러)")
class TraceRuntimeTest {

    private MockedStatic<TcpSender> tcpSenderMock;

    @BeforeEach
    void setUp() {
        TxIdHolder.clear();
        tcpSenderMock = mockStatic(TcpSender.class);
    }

    @AfterEach
    void tearDown() {
        TxIdHolder.clear();
        tcpSenderMock.close();
    }

    @Test
    @DisplayName("인바운드 HTTP 호출 시 유입된 트랜잭션 ID를 최우선으로 사용해야 한다")
    void testOnHttpInStartWithIncomingTxId() {
        String incomingTxId = "upstream-12345";
        TraceRuntime.onHttpInStart("GET", "/api/test", incomingTxId);

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
}
