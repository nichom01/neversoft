package uk.co.neversoft.validate.rules;

import uk.co.neversoft.validate.domain.ValidationOutcome;
import java.util.UUID;

public class DeclarationFact {

    private final UUID customerId;
    private ValidationOutcome outcome = ValidationOutcome.PASSED;
    private String failureReason;

    public DeclarationFact(UUID customerId) {
        this.customerId = customerId;
    }

    public UUID getCustomerId()                    { return customerId; }
    public ValidationOutcome getOutcome()          { return outcome; }
    public String getFailureReason()               { return failureReason; }
    public void setOutcome(ValidationOutcome o)    { this.outcome = o; }
    public void setFailureReason(String r)         { this.failureReason = r; }
}
