package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * B2 (v0.3 groundwork): reads {@code /proc/stat} and computes the CPU
 * utilisation percentage between consecutive samples. Kept as its own
 * bean so {@link MetricsSamplerService} can inject it and unit tests
 * can drive synthetic {@code /proc/stat} files.
 *
 * <p>The first call after a fresh boot has nothing to diff against and
 * returns {@code null} — {@link MetricsSamplerService} treats that as
 * "skip this tick" so the metric_sample table doesn't grow a bogus
 * first-row value.
 *
 * <p>Source: the aggregate {@code cpu} line at the top of {@code /proc/stat},
 * fields:
 * <pre>
 *   cpu  user nice system idle iowait irq softirq steal guest guest_nice
 * </pre>
 * {@code busy} is (user + nice + system + irq + softirq + steal),
 * {@code idle_total} is (idle + iowait). CPU% = 100 * busy_delta /
 * (busy_delta + idle_delta).
 */
@Component
public class ProcStatSampler {

  private static final Logger log = LoggerFactory.getLogger(ProcStatSampler.class);

  private final AuroraProperties props;

  private long prevBusy = -1L;
  private long prevIdle = -1L;

  public ProcStatSampler(AuroraProperties props) {
    this.props = props;
  }

  /**
   * @return CPU% in [0, 100], or null if this is the first sample /
   *         {@code /proc/stat} is unreadable / the delta is zero
   *         (idle box, same tick sampled twice).
   */
  public Double samplePercent() {
    Path p = Path.of(props.hostProcPath()).resolve("stat");
    if (!Files.isRegularFile(p)) p = Path.of("/proc/stat");
    if (!Files.isRegularFile(p)) return null;
    try {
      // First line only — we don't care about per-CPU rows for the
      // system-wide metric.
      String header;
      try (var lines = Files.lines(p)) {
        header = lines.findFirst().orElse(null);
      }
      return computeFromHeader(header);
    } catch (IOException e) {
      log.debug("proc/stat read failed: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Package-private for testing: pass in a synthetic /proc/stat header
   * line and get the CPU% delta against the previously stored sample.
   */
  Double computeFromHeader(String header) {
    if (header == null || !header.startsWith("cpu ") && !header.startsWith("cpu\t")) {
      return null;
    }
    String[] tokens = header.trim().split("\\s+");
    // tokens[0] = "cpu", tokens[1..] = counters.
    if (tokens.length < 5) return null;
    long user = parseLong(tokens[1]);
    long nice = parseLong(tokens[2]);
    long system = parseLong(tokens[3]);
    long idle = parseLong(tokens[4]);
    long iowait = tokens.length > 5 ? parseLong(tokens[5]) : 0;
    long irq = tokens.length > 6 ? parseLong(tokens[6]) : 0;
    long softirq = tokens.length > 7 ? parseLong(tokens[7]) : 0;
    long steal = tokens.length > 8 ? parseLong(tokens[8]) : 0;

    long busy = user + nice + system + irq + softirq + steal;
    long idleAll = idle + iowait;

    if (prevBusy < 0) {
      prevBusy = busy;
      prevIdle = idleAll;
      return null; // no delta yet
    }
    long dBusy = busy - prevBusy;
    long dIdle = idleAll - prevIdle;
    prevBusy = busy;
    prevIdle = idleAll;
    long total = dBusy + dIdle;
    if (total <= 0) return null; // clock stall / zero-delta window
    double pct = 100.0 * dBusy / total;
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    return pct;
  }

  private static long parseLong(String s) {
    try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
  }

  /** Test hook: reset the internal state so the next sample seeds again. */
  void reset() {
    prevBusy = -1L;
    prevIdle = -1L;
  }
}
