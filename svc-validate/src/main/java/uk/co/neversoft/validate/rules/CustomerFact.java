package uk.co.neversoft.validate.rules;

import java.util.UUID;

public class CustomerFact {

    private final UUID id;

    public CustomerFact(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
}
