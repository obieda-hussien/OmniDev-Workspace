package com.omnidev.workspace.domain.neural;

/** Frozen proposal cascade plus a bounded log-probability residual. No prior refusal can become a choice. */
public final class BoundedResidualDecisionModel {
    private final RichIntentRefusalModel teacher;
    private final RichTextEncoder encoder;
    private final TinyNeuralRuntime residual;
    private final double gain;
    public BoundedResidualDecisionModel(RichIntentRefusalModel teacher, RichTextEncoder encoder, TinyNeuralRuntime residual,
                                        double gain, String vocabularyFingerprint) {
        if (teacher == null || encoder == null || residual == null || residual.inputSize() != 1024 || residual.outputSize() != 5 || residual.parameterCount() > 6000 ||
                !Double.isFinite(gain) || gain < 0 || gain > 1 || !encoder.fingerprint().equals(vocabularyFingerprint)) throw new IllegalArgumentException("Invalid bounded residual contract");
        this.teacher = teacher; this.encoder = encoder; this.residual = residual; this.gain = gain;
    }
    public TinyDecisionModel.Decision classify(String text, double minimumProbability, double minimumMargin) {
        TinyDecisionModel.Decision before = teacher.classify(text, minimumProbability, minimumMargin);
        if (gain == 0 || before.status == TinyDecisionModel.Status.ABSTAIN) return before;
        return refine(before, residual.infer(encoder.encode(text)), gain, minimumProbability, minimumMargin);
    }
    static TinyDecisionModel.Decision refine(TinyDecisionModel.Decision before, float[] residual, double gain, double probability, double margin) {
        if (before == null || residual == null || residual.length != 5 || !Double.isFinite(gain) || gain < 0 || gain > 1) throw new IllegalArgumentException("Invalid residual input");
        double[] p = before.probabilitiesCopy(); if (p.length != 5) throw new IllegalArgumentException("Residual label schema changed");
        for (float v : residual) if (!Float.isFinite(v) || Math.abs(v) > 1) throw new IllegalArgumentException("Residual exceeded per-logit bound");
        if (gain == 0 || before.status == TinyDecisionModel.Status.ABSTAIN) return before;
        return TinyDecisionModel.fromDistribution(distribution(p, residual, gain), MultiIntentRefusalModel.labels(), probability, margin);
    }
    static double[] distribution(double[] teacher, float[] correction, double gain) {
        double[] logits = new double[5]; double max = -Double.MAX_VALUE, sum = 0;
        for (int i = 0; i < 5; i++) { logits[i] = Math.log(Math.max(1e-30, teacher[i])) + gain * correction[i]; max = Math.max(max, logits[i]); }
        for (int i = 0; i < 5; i++) { logits[i] = Math.exp(logits[i] - max); sum += logits[i]; }
        for (int i = 0; i < 5; i++) logits[i] /= sum;
        return logits;
    }
}
