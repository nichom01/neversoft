package uk.co.neversoft.validate.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer extends PanacheEntityBase {

    @Id
    public UUID id;
}
