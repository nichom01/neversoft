package uk.co.neversoft.validate.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "validations")
public class Validation extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "declaration_id", nullable = false)
    public UUID declarationId;

    @Column(name = "event_id", nullable = false, unique = true)
    public String eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public ValidationOutcome outcome;

    @Column(name = "failure_reason")
    public String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_applied", columnDefinition = "jsonb", nullable = false)
    public String rulesApplied;

    @Column(name = "validated_at", nullable = false)
    public Instant validatedAt;
}
