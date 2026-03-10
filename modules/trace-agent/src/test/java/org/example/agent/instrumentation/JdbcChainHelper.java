package org.example.agent.instrumentation;

/**
 * Public helper for JDBC integration tests.
 * Named classes in a public top-level class are fully accessible via reflection
 * from any package (including ReflectionUtils in org.example.agent.plugin).
 */
public class JdbcChainHelper {

    public static class FakeConnection {
        private final String url;
        public FakeConnection(String url) { this.url = url; }
        public FakeMeta getMetaData() { return new FakeMeta(url); }
    }

    public static class FakeMeta {
        private final String url;
        public FakeMeta(String url) { this.url = url; }
        public String getURL() { return url; }
    }
}
