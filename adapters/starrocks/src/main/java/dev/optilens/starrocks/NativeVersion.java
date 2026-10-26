package dev.optilens.starrocks;

import dev.optilens.StarRocks;
import java.util.Map;

final class NativeVersion {
  static Map<String, String> verify() throws ReflectiveOperationException {
    Class<?> version = Class.forName("com.starrocks.common.Version");
    String release = (String) version.getField("STARROCKS_VERSION").get(null);
    String commit = (String) version.getField("STARROCKS_COMMIT_HASH").get(null);
    if (!release.equals(StarRocks.VERSION)
        || commit.length() < 7
        || !StarRocks.COMMIT.startsWith(commit))
      throw new IllegalArgumentException(
          "Runtime version mismatch: expected StarRocks "
              + StarRocks.VERSION
              + " / "
              + StarRocks.COMMIT
              + ", found "
              + release
              + " / "
              + commit);
    return Map.of("version", release, "commit", commit);
  }

  private NativeVersion() {}
}
