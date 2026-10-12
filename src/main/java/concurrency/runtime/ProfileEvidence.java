package concurrency.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ProfileEvidence {
    private final String profileId;
    private final String modelVersion;
    private final String modelHash;
    private final String label;
    private final double confidence;
    private final boolean anomaly;
    private final boolean ambiguous;
    private final List<Pattern> patterns;

    public ProfileEvidence(String profileId, String modelVersion, String modelHash, String label,
                           double confidence, boolean anomaly, boolean ambiguous, List<Pattern> patterns) {
        this.profileId = required(profileId, "profileId");
        this.modelVersion = required(modelVersion, "modelVersion");
        this.modelHash = required(modelHash, "modelHash");
        this.label = required(label, "label");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between zero and one");
        }
        this.confidence = confidence;
        this.anomaly = anomaly;
        this.ambiguous = ambiguous;
        this.patterns = Collections.unmodifiableList(new ArrayList<>(patterns == null
                ? Collections.<Pattern>emptyList() : patterns));
    }

    private static String required(String value, String field) {
        if (value == null || value.length() < 3 || value.length() > 128) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    public String getProfileId() { return profileId; }
    public String getModelVersion() { return modelVersion; }
    public String getModelHash() { return modelHash; }
    public String getLabel() { return label; }
    public double getConfidence() { return confidence; }
    public boolean isAnomaly() { return anomaly; }
    public boolean isAmbiguous() { return ambiguous; }
    public List<Pattern> getPatterns() { return patterns; }

    public static final class Pattern {
        private final String kind;
        private final String status;

        public Pattern(String kind, String status) {
            this.kind = required(kind, "pattern kind");
            this.status = required(status, "pattern status");
        }

        public String getKind() { return kind; }
        public String getStatus() { return status; }
    }
}
