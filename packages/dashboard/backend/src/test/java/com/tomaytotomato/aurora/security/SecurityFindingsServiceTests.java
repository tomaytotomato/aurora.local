package com.tomaytotomato.aurora.security;

import com.tomaytotomato.aurora.domain.SecurityFinding;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityFindingsServiceTests {

  private static SecurityRule rule(String id, List<SecurityFinding> findings) {
    SecurityRule r = Mockito.mock(SecurityRule.class);
    Mockito.when(r.id()).thenReturn(id);
    Mockito.when(r.evaluate()).thenReturn(findings);
    return r;
  }

  private static SecurityRule throwingRule(String id) {
    SecurityRule r = Mockito.mock(SecurityRule.class);
    Mockito.when(r.id()).thenReturn(id);
    Mockito.when(r.evaluate()).thenThrow(new RuntimeException("boom"));
    return r;
  }

  private static SecurityFindingsService svc(List<SecurityRule> rules) {
    return new SecurityFindingsService(rules, null);
  }

  @Test
  void empty_rule_list_yields_empty_findings() {
    assertEquals(List.of(), svc(List.of()).allFindings());
    assertEquals(List.of(), svc(null).allFindings());
  }

  @Test
  void aggregates_rule_outputs() {
    var a = rule("r_a", List.of(
        new SecurityFinding("r_a:1", SecurityFinding.LOW, "t1", "d1", null)));
    var b = rule("r_b", List.of(
        new SecurityFinding("r_b:1", SecurityFinding.HIGH, "t2", "d2", null),
        new SecurityFinding("r_b:2", SecurityFinding.MEDIUM, "t3", "d3", null)));
    var s = svc(List.of(a, b));
    var got = s.allFindings();
    assertEquals(3, got.size());
  }

  @Test
  void findings_sorted_by_severity_then_id() {
    var a = rule("r_a", List.of(
        new SecurityFinding("r_a:1", SecurityFinding.LOW, "t1", "d1", null),
        new SecurityFinding("r_a:2", SecurityFinding.HIGH, "t2", "d2", null)));
    var b = rule("r_b", List.of(
        new SecurityFinding("r_b:1", SecurityFinding.HIGH, "t3", "d3", null),
        new SecurityFinding("r_b:2", SecurityFinding.MEDIUM, "t4", "d4", null)));
    var got = svc(List.of(a, b)).allFindings();
    // HIGHs first, id-sorted; then MEDIUMs; then LOWs.
    assertEquals("r_a:2", got.get(0).id());
    assertEquals("r_b:1", got.get(1).id());
    assertEquals("r_b:2", got.get(2).id());
    assertEquals("r_a:1", got.get(3).id());
  }

  @Test
  void rule_exception_does_not_take_down_endpoint() {
    var good = rule("r_good", List.of(
        new SecurityFinding("r_good:1", SecurityFinding.LOW, "t", "d", null)));
    var bad = throwingRule("r_bad");
    var s = svc(List.of(bad, good));
    var got = s.allFindings();
    // Only the good rule contributes.
    assertEquals(1, got.size());
    assertEquals("r_good:1", got.get(0).id());
  }

  @Test
  void rule_returning_null_is_tolerated() {
    var nully = rule("r_null", null);
    var s = svc(List.of(nully));
    assertEquals(List.of(), s.allFindings());
  }

  // -- Dismissal filter (iter-23) --------------------------------------

  @Test
  void active_dismissals_hide_matching_findings_by_default() {
    var r = rule("r_a", List.of(
        new SecurityFinding("r_a:1", SecurityFinding.HIGH, "keep", "d", null),
        new SecurityFinding("r_a:2", SecurityFinding.MEDIUM, "drop", "d", null)));
    var dismissals = Mockito.mock(com.tomaytotomato.aurora.persistence.SecurityDismissalRepo.class);
    Mockito.when(dismissals.activeDismissals(Mockito.any())).thenReturn(java.util.Set.of("r_a:2"));
    var s = new SecurityFindingsService(List.of(r), dismissals);
    var got = s.allFindings();
    assertEquals(1, got.size());
    assertEquals("r_a:1", got.get(0).id());
  }

  @Test
  void includeDismissed_true_surfaces_dismissed_findings() {
    var r = rule("r_a", List.of(
        new SecurityFinding("r_a:1", SecurityFinding.HIGH, "a", "d", null),
        new SecurityFinding("r_a:2", SecurityFinding.MEDIUM, "b", "d", null)));
    var dismissals = Mockito.mock(com.tomaytotomato.aurora.persistence.SecurityDismissalRepo.class);
    Mockito.when(dismissals.activeDismissals(Mockito.any())).thenReturn(java.util.Set.of("r_a:2"));
    var s = new SecurityFindingsService(List.of(r), dismissals);
    var got = s.allFindings(true);
    assertEquals(2, got.size());
    // Never calls the repo when includeDismissed=true.
    Mockito.verify(dismissals, Mockito.never()).activeDismissals(Mockito.any());
  }

  @Test
  void null_dismissal_repo_falls_back_to_no_filter() {
    var r = rule("r_a", List.of(
        new SecurityFinding("r_a:1", SecurityFinding.HIGH, "a", "d", null)));
    var s = new SecurityFindingsService(List.of(r), null);
    assertEquals(1, s.allFindings().size());
    assertEquals(1, s.allFindings(true).size());
  }
}
