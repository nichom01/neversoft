package uk.co.neversoft.risk.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class StubRiskScorer implements RiskScorer {

    @Override
    public double score(UUID declarationId) {
        return 0.0;
    }

    @Override
    public String band(double score) {
        return "LOW";
    }
}
