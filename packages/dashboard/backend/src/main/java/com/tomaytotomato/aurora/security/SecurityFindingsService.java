package com.tomaytotomato.aurora.security;

import com.tomaytotomato.aurora.domain.SecurityFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * B4 (v0.3): aggregates {@link SecurityRule} beans into a single
 * {@code List<SecurityFinding>}. Spring autowires every SecurityRule
 * bean into the constructor, so registering a new rule is a
 * {@code @Component} on a class implementing the interface \u2014 no
 * wiring changes elsewhere.
 *
 * <p>Ordering: HIGH first, then MEDIUM, then LOW; ties broken by
 * finding id so the frontend rendering is stable across polls.
 *
 * <p>Isolation: each rule is invoked inside a try/catch so a broken
 * rule can't hide the others. A rule that throws logs at DEBUG and
 * contributes zero findings.
 */
@Service
public class SecurityFindingsService {

  private static final Logger log = LoggerFactory.getLogger(SecurityFindingsService.class);

  private static final Map<String, Integer> SEVERITY_RANK = Map.of(
      SecurityFinding.HIGH, 0,
      SecurityFinding.MEDIUM, 1,
      SecurityFinding.LOW, 2
  );

  private final List<SecurityRule> rules;

  public SecurityFindingsService(List<SecurityRule> rules) {
    this.rules = rules == null ? List.of() : List.copyOf(rules);
  }

  public List<SecurityFinding> allFindings() {
    List<SecurityFinding> out = new ArrayList<>();
    for (SecurityRule r : rules) {
      try {
        List<SecurityFinding> ruleFindings = r.evaluate();
        if (ruleFindings != null) out.addAll(ruleFindings);
      } catch (Exception e) {
        // Never let a rule's failure bring down the endpoint.
        log.debug("security rule {} threw: {}", r.id(), e.getMessage());
      }
    }
    out.sort(Comparator
        .comparingInt((SecurityFinding f) ->
            SEVERITY_RANK.getOrDefault(f.severity(), 99))
        .thenComparing(SecurityFinding::id));
    return out;
  }
}
