package dev.optilens;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalAccessTest {
  @Test void acceptsLoopbackAuthoritiesRegardlessOfBackendPort() {
    for (String host : new String[]{"localhost:49894", "127.0.0.1:49894", "[::1]:49894",
        "LOCALHOST:8080", "localhost", "127.0.0.1", "[::1]", "localhost:65535"}) {
      assertTrue(LocalAccess.allowsHost(host), host);
    }
  }

  @Test void rejectsNonLoopbackAndMalformedAuthorities() {
    assertFalse(LocalAccess.allowsHost(null));
    for (String host : new String[]{"", "example.org:8080", "localhost.evil:49894",
        "localhost:0", "localhost:65536", "localhost:-1", "localhost:", "localhost:abc",
        "localhost:49894/path", "localhost:49894?x", "localhost:49894#x", "localhost@evil",
        "evil@localhost:49894", "localhost:49894,example.org", " localhost:49894", "127.0.0.2:8080"}) {
      assertFalse(LocalAccess.allowsHost(host), host);
    }
  }

  @Test void permitsLoopbackOriginsWhenProxyChangesPortOrHost() {
    assertTrue(LocalAccess.allowsOrigin(null));
    for (String origin : new String[]{"http://localhost:49894", "http://127.0.0.1:49894",
        "http://[::1]:49894", "https://localhost:49894", "http://localhost"}) {
      assertTrue(LocalAccess.allowsOrigin(origin), origin);
    }
  }

  @Test void rejectsExternalOpaqueAndMalformedOrigins() {
    for (String origin : new String[]{"null", "", "https://example.org", "http://localhost.evil:49894",
        "http://evil@localhost:49894", "http://localhost:49894/", "http://localhost:49894/path",
        "http://localhost:49894?x", "http://localhost:49894#x", "file://localhost", "//localhost:49894",
        "http://localhost:65536", "http://localhost:49894 https://evil.example"}) {
      assertFalse(LocalAccess.allowsOrigin(origin), origin);
    }
  }
}
