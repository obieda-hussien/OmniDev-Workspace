package com.omnidev.workspace.domain.neural;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Experimental evidence retrieval baseline; NOT a faithful Mega-RAM weight-space port. */
public final class OmniRamLite {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final int CHUNK_SIZE = 900;
    private static final int CHUNK_STRIDE = 750;
    private static final int MAX_DOCUMENTS = 128;
    private static final int MAX_CHARS = 1_000_000;

    public static final class Document {
        public final String id;
        public final String text;

        public Document(String id, String text) {
            if (id == null || id.trim().isEmpty() || id.length() > 160 || text == null) {
                throw new IllegalArgumentException("Invalid document");
            }
            this.id = id;
            this.text = text;
        }
    }

    public static final class Hit {
        public final String documentId;
        public final int startOffset;
        public final int endOffset;
        public final String quote;
        public final double score;

        private Hit(String documentId, int startOffset, int endOffset, String quote, double score) {
            this.documentId = documentId;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.quote = quote;
            this.score = score;
        }
    }

    private static final class Chunk {
        final String id, quote, normalized;
        final int start, end, length;
        final Map<String, Integer> counts;

        Chunk(Document d, int start, int end) {
            this.id = d.id;
            this.start = start;
            this.end = end;
            this.quote = d.text.substring(start, end);
            this.normalized = normalize(quote);
            this.counts = countTerms(quote);
            int n = 0;
            for (int count : counts.values()) n += count;
            this.length = n;
        }
    }

    private final List<Chunk> chunks;
    private final Map<String, Integer> documentFrequency;
    private final double averageLength;

    public OmniRamLite(List<Document> docs) {
        if (docs == null || docs.size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("Exceeded document limit");
        }
        List<Chunk> indexed = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        long size = 0;
        for (Document doc : docs) {
            if (doc == null || !ids.add(doc.id)) throw new IllegalArgumentException("Duplicate/invalid id");
            size += doc.text.length();
            if (size > MAX_CHARS) throw new IllegalArgumentException("Exceeded corpus limit");
            for (int offset = 0; offset < doc.text.length(); offset += CHUNK_STRIDE) {
                int end = Math.min(doc.text.length(), offset + CHUNK_SIZE);
                indexed.add(new Chunk(doc, offset, end));
                if (end == doc.text.length()) break;
            }
        }
        chunks = Collections.unmodifiableList(indexed);
        documentFrequency = new HashMap<>();
        double lengthSum = 0;
        for (Chunk c : chunks) {
            lengthSum += c.length;
            for (String term : c.counts.keySet()) {
                documentFrequency.put(term, documentFrequency.getOrDefault(term, 0) + 1);
            }
        }
        averageLength = chunks.isEmpty() ? 1.0 : Math.max(1.0, lengthSum / chunks.size());
    }

    public List<Hit> search(String query, int limit) {
        if (query == null || query.length() > 2048 || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Invalid retrieval request");
        }
        Set<String> queryTerms = countTerms(query).keySet();
        if (queryTerms.isEmpty() || chunks.isEmpty()) return Collections.emptyList();
        String normalizedQuery = normalize(query);
        Set<String> queryTrigrams = trigrams(normalizedQuery);
        List<Hit> hits = new ArrayList<>();
        for (Chunk chunk : chunks) {
            double bm25 = 0;
            int overlap = 0;
            for (String term : queryTerms) {
                Integer tf = chunk.counts.get(term);
                if (tf == null) continue;
                overlap++;
                int df = documentFrequency.get(term);
                double idf = Math.log(1.0 + (chunks.size() - df + 0.5) / (df + 0.5));
                double norm = tf + 1.2 * (0.25 + 0.75 * chunk.length / averageLength);
                bm25 += idf * (tf * 2.2) / norm;
            }
            // No lexical overlap -> abstain rather than invent a semantic match.
            if (overlap == 0) continue;
            double charScore = jaccard(queryTrigrams, trigrams(chunk.normalized));
            double coverage = (double) overlap / queryTerms.size();
            double directPhrase = chunk.normalized.contains(normalizedQuery) ? 1.0 : 0.0;
            double score = bm25 + 0.6 * charScore + 0.4 * coverage + 0.5 * directPhrase;
            hits.add(new Hit(chunk.id, chunk.start, chunk.end, chunk.quote, score));
        }
        hits.sort(Comparator.comparingDouble((Hit h) -> h.score).reversed()
                .thenComparing(h -> h.documentId).thenComparingInt(h -> h.startOffset));
        return Collections.unmodifiableList(new ArrayList<>(hits.subList(0, Math.min(limit, hits.size()))));
    }

    private static Map<String, Integer> countTerms(String value) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = WORD.matcher(normalize(value));
        while (matcher.find()) {
            String term = matcher.group();
            if (term.length() > 1) counts.put(term, counts.getOrDefault(term, 0) + 1);
        }
        return counts;
    }

    private static String normalize(String value) {
        String result = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKC);
        return result.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ٱ', 'ا').replace('ى', 'ي');
    }

    private static Set<String> trigrams(String text) {
        String compact = text.replaceAll("\\s+", " ");
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 3 <= compact.length(); i++) result.add(compact.substring(i, i + 3));
        return result;
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        int intersect = 0;
        for (String item : left) if (right.contains(item)) intersect++;
        return (double) intersect / (left.size() + right.size() - intersect);
    }
}