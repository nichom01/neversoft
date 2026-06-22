// Quickstart Scenario 1: this comment is the change that triggers selective CI for declare-svc only.
package uk.co.neversoft.declare.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import uk.co.neversoft.declare.service.DeclarationService;

import java.net.URI;

@Path("/declarations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeclarationResource {

    @Inject
    DeclarationService service;

    @POST
    public Response create(CreateDeclarationRequest request) {
        var result = service.create(request);
        if (result.created()) {
            return Response.created(URI.create("/declarations/" + result.declarationId()))
                    .entity(result.declarationId())
                    .build();
        }
        return Response.ok(result.declarationId()).build();
    }
}
