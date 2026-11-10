package com.omnidev.workspace.domain.neural;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Macro retrieval metrics for LABELLED, disjoint benchmarking data. */
public final class EvidenceMetrics {
    private EvidenceMetrics() { }

    public static final class Case {
        public final Set<String> relevantDocumentIds;
        public final List<String> retrievedDocumentIds;
        public Case(Set<String> relevantDocumentIds, List<String> retrievedDocumentIds) {
            if (relevantDocumentIds == null || relevantDocumentIds.isEmpty() ||
                    relevantDocumentIds.contains(null) || retrievedDocumentIds == null ||
                    retrievedDocumentIds.contains(null)) throw new IllegalArgumentException("Invalid case");
            this.relevantDocumentIds = Collections.unmodifiableSet(new HashSet<>(relevantDocumentIds));
            this.retrievedDocumentIds = Collections.unmodifiableList(new ArrayList<>(retrievedDocumentIds));
        }
    }

    public static final class Summary {
        public final double recallAtK, mrrAtK, ndcgAtK;
        public final int queries;
        private Summary(double recall, double mrr, double ndcg, int queries) {
            this.recallAtK = recall;
            this.mrrAtK = mrr;
            this.ndcgAtK = ndcg;
            this.queries = queries;
        }
    }

    public static Summary evaluate(List<Case> cases, int k) {
        if (cases == null || cases.isEmpty() || k < 1 || k > 100) {
            throw new IllegalArgumentException("Invalid evaluation set");
        }
        double recall = 0, mrr = 0, ndcg = 0;
        for (Case item : cases) {
            if (item == null) throw new IllegalArgumentException("Null case");
            Set<String> seen = new HashSet<>();
            int relevant = 0;
            double dcg = 0, rr = 0;
            // Duplicates are not additional hits and still consume rank positions.
            for (int position = 0; position < Math.min(k, item.retrievedDocumentIds.size()); position++) {
                String id = item.retrievedDocumentIds.get(position);
                if (!seen.add(id) || !item.relevantDocumentIds.contains(id)) continue;
                relevant++;
                double discount = 1.0 / log2(position + 2);
                dcg += discount;
                if (rr == 0) rr = 1.0 / (position + 1);
            }
            double ideal = 0;
            for (int position = 0; position < Math.min(k, item.relevantDocumentIds.size()); position++) {
                ideal += 1.0 / log2(position + 2);
            }
            recall += (double) relevant / item.relevantDocumentIds.size();
            mrr += rr;
            ndcg += dcg / ideal;
        }
        return new Summary(recall / cases.size(), mrr / cases.size(), ndcg / cases.size(), cases.size());
    }

    public static boolean citationsMatch(List<OmniRamLite.Document> corpus, List<OmniRamLite.Hit> hits) {
        if (corpus == null || hits == null) return false;
        java.util.Map<String, String> texts = new java.util.HashMap<>();
        for (OmniRamLite.Document d : corpus) {
            if (d == null || texts.put(d.id, d.text) != null) return false;
        }
        for (OmniRamLite.Hit hit : hits) {
            if (hit == null) return false;
            String source = texts.get(hit.documentId);
            if (source == null || hit.startOffset < 0 || hit.endOffset < hit.startOffset ||
                    hit.endOffset > source.length() ||
                    !source.substring(hit.startOffset, hit.endOffset).equals(hit.quote)) return false;
        }
        return true;
    }

    private static double log2(int value) { return Math.log(value) / Math.log(2.0); }
}