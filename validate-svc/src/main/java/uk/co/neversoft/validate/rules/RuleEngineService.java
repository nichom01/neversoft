package uk.co.neversoft.validate.rules;

import jakarta.enterprise.context.ApplicationScoped;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;

@ApplicationScoped
public class RuleEngineService {

    private final KieContainer container;

    public RuleEngineService() {
        container = KieServices.Factory.get().getKieClasspathContainer();
    }

    public KieSession newSession() {
        return container.newKieSession("validateSession");
    }
}
