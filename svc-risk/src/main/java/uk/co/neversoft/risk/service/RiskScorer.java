package uk.co.neversoft.risk.service;

import java.util.UUID;

public interface RiskScorer {
    double score(UUID declarationId);
    String band(double score);
}
