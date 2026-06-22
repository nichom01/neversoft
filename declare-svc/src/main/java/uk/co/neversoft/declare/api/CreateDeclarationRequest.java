package uk.co.neversoft.declare.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public class CreateDeclarationRequest {
    public UUID customerId;
    public String idempotencyKey;
    public JsonNode payload;
}
