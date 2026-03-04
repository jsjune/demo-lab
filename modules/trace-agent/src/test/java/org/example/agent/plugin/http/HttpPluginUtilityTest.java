package org.example.agent.plugin.http;

import org.example.agent.config.AgentConfig;
import org.example.agent.testutil.TestStateGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class HttpPluginUtilityTest {
    private TestStateGuard stateGuard;

    @BeforeEach
    void setUp() throws Exception {
        stateGuard = new TestStateGuard();
        stateGuard.snapshotPropertiesField(AgentConfig.class, "props");
    }

    @AfterEach
    void tearDown() {
        stateGuard.close();
    }

    @Test
    void injectHeadersToRequest_addsTxIdAndSpanId() {
        HeaderRequest request = new HeaderRequest();

        HttpPlugin.injectHeadersToRequest(request, "tx-1", "sp-1");

        assertEquals("tx-1", request.headers.getFirst(AgentConfig.getHeaderKey()));
        assertEquals("sp-1", request.headers.getFirst("X-Span-Id"));
    }

    @Test
    void injectHeadersToRequest_ignoresNullTxId() {
        HeaderRequest request = new HeaderRequest();

        HttpPlugin.injectHeadersToRequest(request, null, "sp-1");

        assertFalse(request.headers.containsKey(AgentConfig.getHeaderKey()));
        assertFalse(request.headers.containsKey("X-Span-Id"));
    }

    @Test
    void rebuildClientRequestWithHeaders_returnsSameWhenTxIdIsNull() {
        ClientRequest req = ClientRequest.create(HttpMethod.GET, URI.create("http://localhost/test")).build();

        Object out = HttpPlugin.rebuildClientRequestWithHeaders(req, null, "sp-1");

        assertSame(req, out);
    }

    @Test
    void rebuildClientRequestWithHeaders_addsTxIdAndSpanId() {
        ClientRequest req = ClientRequest.create(HttpMethod.GET, URI.create("http://localhost/test")).build();

        Object out = HttpPlugin.rebuildClientRequestWithHeaders(req, "tx-2", "sp-2");

        assertInstanceOf(ClientRequest.class, out);
        ClientRequest rebuilt = (ClientRequest) out;
        assertEquals("tx-2", rebuilt.headers().getFirst(AgentConfig.getHeaderKey()));
        assertEquals("sp-2", rebuilt.headers().getFirst("X-Span-Id"));
    }

    @Test
    void rebuildClientRequestWithHeaders_whenRequestIsNull_returnsNull() {
        Object out = HttpPlugin.rebuildClientRequestWithHeaders(null, "tx-3", "sp-3");
        assertNull(out);
    }

    static final class HeaderRequest {
        private final HttpHeaders headers = new HttpHeaders();

        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
