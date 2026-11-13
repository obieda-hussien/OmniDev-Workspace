package com.omnidev.workspace.domain.neural;

import com.omnidev.workspace.domain.neural.PublicRefusalCorpus.Row;
import java.util.*;

/**
 * Fit independent prior-domain trust thresholds against actual outcome utility and individual
 * retention.
 */
public final class ExpertPolicyTrainer {
  static final class Observation {
    final Row row;
    final TinyDecisionModel.Decision before, expert;
    final double support;

    Observation(
        Row row,
        TinyDecisionModel.Decision before,
        TinyDecisionModel.Decision expert,
        double support) {
      this.row = row;
      this.before = before;
      this.expert = expert;
      this.support = support;
    }
  }

  static final class Policy {
    final double[] reject, change, support;
    final double utility;

    Policy(double[] reject, double[] change, double[] support, double utility) {
      this.reject = reject.clone();
      this.change = change.clone();
      this.support = support.clone();
      this.utility = utility;
    }
  }

  static Policy fit(List<Observation> rows, List<Observation> rehearsal) {
    if (rows == null || rows.isEmpty() || rows.size() > 10_000)
      throw new IllegalArgumentException("Invalid bounded policy observations");
    Map<String, Integer> totals = new TreeMap<>();
    for (Observation o : rows) {
      if (!o.row.split.equals("development"))
        throw new IllegalArgumentException("Policy fitting requires development only");
      totals.merge(o.row.language + ":" + o.row.label, 1, Integer::sum);
    }
    if (rehearsal == null || rehearsal.size() > 10_000)
      throw new IllegalArgumentException("Invalid bounded policy rehearsal");
    for (Observation o : rehearsal)
      if (!o.row.split.equals("train")
          || o.row.label != 0
          || !Objects.equals(o.before.choice, o.row.expected))
        throw new IllegalArgumentException(
            "Rehearsal requires previously correct known training rows");
    double[] reject = new double[5], change = new double[5], support = new double[5];
    Arrays.fill(reject, 1.1);
    Arrays.fill(change, 1.1);
    Arrays.fill(support, .5);
    for (int domain = 0; domain < 5; domain++) {
      double best = -1;
      for (double r : new double[] {1.1, .99, .95, .9, .8, .7, .6, .5})
        for (double c : new double[] {1.1, .99, .95, .9, .8, .7, .6})
          for (double s : new double[] {.5, .6, .7, .8, .9, .95, 1.1}) {
            double score = 0;
            boolean feasible = true;
            for (Observation o : rows)
              if (o.before.status == TinyDecisionModel.Status.CHOICE
                  && UtilityExpertDecisionModel.domain(o.before.choice) == domain) {
                TinyDecisionModel.Decision after =
                    UtilityExpertDecisionModel.choose(o.before, o.expert, r, c, o.support, s);
                boolean correct = correct(o.row, after);
                if (o.row.label == 0 && Objects.equals(o.before.choice, o.row.expected) && !correct)
                  feasible = false;
                if (correct) score += 1.0 / totals.get(o.row.language + ":" + o.row.label);
              }
            if (feasible)
              for (Observation o : rehearsal)
                if (UtilityExpertDecisionModel.domain(o.before.choice) == domain
                    && !correct(
                        o.row,
                        UtilityExpertDecisionModel.choose(
                            o.before, o.expert, r, c, o.support, s))) {
                  feasible = false;
                  break;
                }
            if (feasible && score > best + 1e-12) {
              best = score;
              reject[domain] = r;
              change[domain] = c;
              support[domain] = s;
            }
          }
    }
    double sum = 0;
    for (Observation o : rows) {
      int d =
          o.before.status == TinyDecisionModel.Status.CHOICE
              ? UtilityExpertDecisionModel.domain(o.before.choice)
              : 0;
      TinyDecisionModel.Decision after =
          o.before.status == TinyDecisionModel.Status.ABSTAIN
              ? o.before
              : UtilityExpertDecisionModel.choose(
                  o.before, o.expert, reject[d], change[d], o.support, support[d]);
      if (o.row.label == 0
          && Objects.equals(o.before.choice, o.row.expected)
          && !correct(o.row, after))
        throw new AssertionError("Policy lost an earlier correct development choice");
      if (correct(o.row, after)) sum += 1.0 / totals.get(o.row.language + ":" + o.row.label);
    }
    return new Policy(reject, change, support, sum / totals.size());
  }

  static boolean correct(Row row, TinyDecisionModel.Decision decision) {
    return row.label == 1
        ? decision.status == TinyDecisionModel.Status.ABSTAIN
        : Objects.equals(row.expected, decision.choice);
  }

  private ExpertPolicyTrainer() {}
}
