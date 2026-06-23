package uk.co.neversoft.risk.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_assessments")
public class RiskAssessment extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "declaration_id", nullable = false)
    public UUID declarationId;

    @Column(name = "validation_id", nullable = false)
    public UUID validationId;

    @Column(name = "event_id", nullable = false, unique = true)
    public String eventId;

    @Column(nullable = false)
    public double score;

    @Column(nullable = false)
    public String band;

    @Column(name = "assessed_at", nullable = false)
    public Instant assessedAt;
}
