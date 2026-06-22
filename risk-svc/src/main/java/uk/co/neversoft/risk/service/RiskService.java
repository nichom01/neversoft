package uk.co.neversoft.risk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import uk.co.neversoft.risk.domain.OutboxEntry;
import uk.co.neversoft.risk.domain.RiskAssessment;
import uk.co.neversoft.risk.messaging.ValidationCompletedEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class RiskService {

    private static final Logger LOG = Logger.getLogger(RiskService.class);

    @Inject
    RiskScorer scorer;

    @Transactional
    public void assess(ValidationCompletedEvent event) {
        if ("FAILED".equals(event.outcome())) {
            return;
        }
        if (RiskAssessment.find("eventId", event.eventId()).count() > 0) {
            return;
        }

        UUID declarationId = UUID.fromString(event.declarationId());
        UUID validationId  = UUID.fromString(event.aggregateId());
        double score = scorer.score(declarationId);
        String band  = scorer.band(score);

        RiskAssessment assessment = new RiskAssessment();
        assessment.id            = UUID.randomUUID();
        assessment.declarationId = declarationId;
        assessment.validationId  = validationId;
        assessment.eventId       = event.eventId();
        assessment.score         = score;
        assessment.band          = band;
        assessment.assessedAt    = Instant.now();
        assessment.persist();

        OutboxEntry outbox = new OutboxEntry();
        outbox.id            = UUID.randomUUID();
        outbox.aggregateType = "risk.assessed";
        outbox.aggregateId   = assessment.id;
        outbox.eventType     = "risk.assessed";
        outbox.payload       = buildEventPayload(outbox.id, event, assessment);
        outbox.createdAt     = assessment.assessedAt;
        outbox.persist();

        LOG.infof("risk.assessed declarationId=%s band=%s score=%.1f eventId=%s",
                assessment.declarationId, assessment.band, assessment.score, outbox.id);
    }

    static String buildEventPayload(UUID eventId, ValidationCompletedEvent source, RiskAssessment assessment) {
        try {
            var m = new ObjectMapper();
            m.findAndRegisterModules();
            return m.writeValueAsString(Map.of(
                    "eventId",       eventId.toString(),
                    "eventType",     "risk.assessed",
                    "aggregateId",   assessment.id.toString(),
                    "declarationId", assessment.declarationId.toString(),
                    "validationId",  assessment.validationId.toString(),
                    "score",         assessment.score,
                    "band",          assessment.band,
                    "occurredAt",    assessment.assessedAt.toString()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise risk event payload", e);
        }
    }
}
