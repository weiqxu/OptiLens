package dev.optilens;

import java.net.URI;
import java.util.regex.Pattern;

/** Literal loopback authorities; never resolve a client-supplied hostname through DNS. */
final class LocalAccess {
  private static final Pattern AUTHORITY =
      Pattern.compile(
          "(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::([0-9]{1,5}))?", Pattern.CASE_INSENSITIVE);

  static boolean allowsHost(String authority) {
    if (authority == null) return false;
    var match = AUTHORITY.matcher(authority);
    if (!match.matches()) return false;
    if (match.group(1) == null) return true;
    int port = Integer.parseInt(match.group(1));
    return port >= 1 && port <= 65535;
  }

  static boolean allowsOrigin(String origin) {
    // CLI clients need not send Origin. Browser origins must still be literal loopback URLs.
    if (origin == null) return true;
    try {
      var uri = URI.create(origin);
      return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          && allowsHost(uri.getRawAuthority())
          && uri.getRawUserInfo() == null
          && "".equals(uri.getRawPath())
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private LocalAccess() {}
}
