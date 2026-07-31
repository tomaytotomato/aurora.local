package com.tomaytotomato.aurora.services;

import com.tomaytotomato.aurora.config.AuroraProperties;
import com.tomaytotomato.aurora.domain.RepoState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read/write the aurora.local repo files that the dashboard is allowed to touch:
 * <ul>
 *   <li>{@code .state.yml} — hostname, domain, enabled[]</li>
 *   <li>{@code packages/*&#47;.env.example} — read-only template</li>
 *   <li>{@code packages/*&#47;.env}         — key/value mutation (v0.2)</li>
 * </ul>
 *
 * <p>All writes are parse-modify-serialize (never string-replace), per §11.9 of
 * the brief. YAML is round-tripped with SnakeYAML block style.
 */
@Service
public class StateFileService {

  private static final Logger log = LoggerFactory.getLogger(StateFileService.class);

  private final AuroraProperties props;

  public StateFileService(AuroraProperties props) {
    this.props = props;
  }

  public Path repoRoot() {
    return Path.of(props.repoPath());
  }

  public Path stateFile() {
    return repoRoot().resolve(".state.yml");
  }

  @SuppressWarnings("unchecked")
  public RepoState readState() {
    Path p = stateFile();
    if (!Files.exists(p)) {
      return new RepoState(null, null, null, null, List.of(), List.of());
    }
    try (var in = Files.newInputStream(p)) {
      Map<String, Object> data = new Yaml().load(in);
      if (data == null) data = Map.of();
      return new RepoState(
          asInt(data.get("bootstrap_version")),
          (String) data.get("hostname"),
          (String) data.get("domain"),
          (String) data.get("installed_at"),
          asStringList(data.get("enabled")),
          asStringList(data.get("profiles"))
      );
    } catch (IOException e) {
      throw new RuntimeException("failed to read " + p, e);
    }
  }

  /** Update just the domain field, preserving other keys. */
  public void writeDomain(String domain) {
    mutateState(m -> m.put("domain", domain));
  }

  /** Overwrite the enabled[] list, preserving other keys. */
  public void writeEnabled(List<String> enabled) {
    mutateState(m -> m.put("enabled", new ArrayList<>(enabled)));
  }

  @SuppressWarnings("unchecked")
  private void mutateState(java.util.function.Consumer<Map<String, Object>> mutator) {
    Path p = stateFile();
    Map<String, Object> data;
    try {
      if (Files.exists(p)) {
        try (var in = Files.newInputStream(p)) {
          data = new Yaml().load(in);
        }
      } else {
        data = new LinkedHashMap<>();
      }
      if (data == null) data = new LinkedHashMap<>();
      mutator.accept(data);
      var dumper = new DumperOptions();
      dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      var yaml = new Yaml(dumper);
      try (Writer w = Files.newBufferedWriter(p)) {
        yaml.dump(data, w);
      }
      log.info("wrote {}", p);
    } catch (IOException e) {
      throw new RuntimeException("failed to write " + p, e);
    }
  }

  private static Integer asInt(Object o) {
    if (o instanceof Number n) return n.intValue();
    if (o instanceof String s) try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static List<String> asStringList(Object o) {
    if (o instanceof List<?> list) {
      var out = new ArrayList<String>(list.size());
      for (Object x : list) if (x != null) out.add(x.toString());
      return List.copyOf(out);
    }
    return List.of();
  }
}
