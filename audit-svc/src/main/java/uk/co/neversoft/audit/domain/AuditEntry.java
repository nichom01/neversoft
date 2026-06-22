package uk.co.neversoft.audit.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditEntry extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    public String eventId;

    @Column(nullable = false)
    public String topic;

    @Column(name = "event_type", nullable = false)
    public String eventType;

    @Column(name = "aggregate_id", nullable = false)
    public String aggregateId;

    @Column(name = "raw_payload", columnDefinition = "jsonb", nullable = false)
    public String rawPayload;

    @Column(name = "received_at", nullable = false)
    public Instant receivedAt;
}
