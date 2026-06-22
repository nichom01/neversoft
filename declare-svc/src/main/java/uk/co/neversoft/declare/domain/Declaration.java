package uk.co.neversoft.declare.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "declarations")
public class Declaration extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "customer_id", nullable = false)
    public UUID customerId;

    @Column(columnDefinition = "jsonb", nullable = false)
    public String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public DeclarationStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    public String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
