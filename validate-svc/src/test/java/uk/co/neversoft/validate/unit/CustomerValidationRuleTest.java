package uk.co.neversoft.validate.unit;

import org.junit.jupiter.api.*;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieSession;
import uk.co.neversoft.validate.domain.ValidationOutcome;
import uk.co.neversoft.validate.rules.CustomerFact;
import uk.co.neversoft.validate.rules.DeclarationFact;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CustomerValidationRuleTest {

    private KieSession session;

    @BeforeEach
    void setup() {
        session = KieServices.Factory.get()
                .getKieClasspathContainer()
                .newKieSession("validateSession");
    }

    @AfterEach
    void teardown() {
        session.dispose();
    }

    @Test
    void knownCustomer_outcome_PASSED() {
        UUID customerId = UUID.randomUUID();
        session.insert(new CustomerFact(customerId));
        DeclarationFact fact = new DeclarationFact(customerId);
        session.insert(fact);

        session.fireAllRules();

        assertEquals(ValidationOutcome.PASSED, fact.getOutcome());
        assertNull(fact.getFailureReason());
    }

    @Test
    void unknownCustomer_outcome_FAILED_withReason() {
        UUID customerId = UUID.randomUUID();
        DeclarationFact fact = new DeclarationFact(customerId);
        session.insert(fact);

        session.fireAllRules();

        assertEquals(ValidationOutcome.FAILED, fact.getOutcome());
        assertNotNull(fact.getFailureReason());
        assertTrue(fact.getFailureReason().contains(customerId.toString()));
    }

    @Test
    void differentCustomerInSession_doesNotMatchDeclaration() {
        session.insert(new CustomerFact(UUID.randomUUID()));
        UUID unknownId = UUID.randomUUID();
        DeclarationFact fact = new DeclarationFact(unknownId);
        session.insert(fact);

        session.fireAllRules();

        assertEquals(ValidationOutcome.FAILED, fact.getOutcome());
    }
}
