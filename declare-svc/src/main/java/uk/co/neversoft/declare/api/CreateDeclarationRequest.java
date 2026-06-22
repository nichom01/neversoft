package uk.co.neversoft.declare.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.UUID;

@RegisterForReflection
public class CreateDeclarationRequest {
    public UUID customerId;
    public String idempotencyKey;
    public JsonNode payload;
}
