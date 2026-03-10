package org.example.agent.plugin.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpPluginTransformerCoverageTest {

    @Test
    void pluginMetadata() {
        HttpPlugin p = new HttpPlugin();
        assertEquals("http", p.pluginId());
        assertTrue(p.requiresBootstrapSearch());
    }

    // -----------------------------------------------------------------------
    // getRequestMethod
    // -----------------------------------------------------------------------

    @Test
    void getRequestMethod_null_returnsUnknown() {
        assertEquals("UNKNOWN", HttpPlugin.getRequestMethod(null));
    }

    @Test
    void getRequestMethod_reflectsGetMethod() {
        assertEquals("POST", HttpPlugin.getRequestMethod(new FakeRequest()));
    }

    // -----------------------------------------------------------------------
    // getRequestURI
    // -----------------------------------------------------------------------

    @Test
    void getRequestURI_null_returnsSlashUnknown() {
        assertEquals("/unknown", HttpPlugin.getRequestURI(null));
    }

    @Test
    void getRequestURI_reflectsGetRequestURI() {
        assertEquals("/api/items", HttpPlugin.getRequestURI(new FakeRequest()));
    }

    // -----------------------------------------------------------------------
    // getRequestHeader
    // -----------------------------------------------------------------------

    @Test
    void getRequestHeader_nullRequest_returnsNull() {
        assertNull(HttpPlugin.getRequestHeader(null, "X-Tx-Id"));
    }

    @Test
    void getRequestHeader_nullName_returnsNull() {
        assertNull(HttpPlugin.getRequestHeader(new FakeRequest(), null));
    }

    @Test
    void getRequestHeader_reflectsGetHeader() {
        assertEquals("hdr-X-Tx-Id", HttpPlugin.getRequestHeader(new FakeRequest(), "X-Tx-Id"));
    }

    // -----------------------------------------------------------------------
    // getResponseStatus
    // -----------------------------------------------------------------------

    @Test
    void getResponseStatus_null_returns200() {
        assertEquals(200, HttpPlugin.getResponseStatus(null));
    }

    @Test
    void getResponseStatus_reflectsGetStatus() {
        assertEquals(404, HttpPlugin.getResponseStatus(new FakeResponse()));
    }

    // -----------------------------------------------------------------------
    // isAsyncStarted
    // -----------------------------------------------------------------------

    @Test
    void isAsyncStarted_null_returnsFalse() {
        assertFalse(HttpPlugin.isAsyncStarted(null));
    }

    @Test
    void isAsyncStarted_reflectsIsAsyncStarted() {
        assertTrue(HttpPlugin.isAsyncStarted(new FakeRequest()));
    }

    // -----------------------------------------------------------------------
    // getClientRequestMethod / getClientRequestUrl
    // -----------------------------------------------------------------------

    @Test
    void getClientRequestMethod_null_returnsUnknown() {
        assertEquals("UNKNOWN", HttpPlugin.getClientRequestMethod(null));
    }

    @Test
    void getClientRequestUrl_null_returnsUnknownUrl() {
        assertEquals("unknown-url", HttpPlugin.getClientRequestUrl(null));
    }

    // -----------------------------------------------------------------------
    // Helper stubs
    // -----------------------------------------------------------------------

    public static class FakeRequest {
        public String getMethod()     { return "POST"; }
        public String getRequestURI() { return "/api/items"; }
        public String getHeader(String name) { return "hdr-" + name; }
        public boolean isAsyncStarted() { return true; }
    }

    public static class FakeResponse {
        public int getStatus() { return 404; }
    }
}
