package uk.co.neversoft.audit.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import uk.co.neversoft.audit.domain.AuditEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public class AuditResource {

    @Inject
    ObjectMapper mapper;

    public record AuditEntryResponse(
        UUID id,
        String eventId,
        String topic,
        String eventType,
        String aggregateId,
        JsonNode rawPayload,
        Instant receivedAt
    ) {}

    @GET
    public List<AuditEntryResponse> list(
            @QueryParam("topic") String topic,
            @QueryParam("aggregateId") String aggregateId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) throws Exception {

        var sort = Sort.by("receivedAt").descending();
        var query = topic != null && aggregateId != null
                ? AuditEntry.find("topic = ?1 and aggregateId = ?2", sort, topic, aggregateId)
                : topic != null
                ? AuditEntry.find("topic = ?1", sort, topic)
                : aggregateId != null
                ? AuditEntry.find("aggregateId = ?1", sort, aggregateId)
                : AuditEntry.findAll(sort);

        return toResponse(query.page(page, size).list());
    }

    @GET
    @Path("/{id}")
    public AuditEntryResponse getById(@PathParam("id") UUID id) throws Exception {
        AuditEntry entry = AuditEntry.findById(id);
        if (entry == null) throw new NotFoundException();
        return toResponse(entry);
    }

    private List<AuditEntryResponse> toResponse(List<AuditEntry> entries) throws Exception {
        var result = new ArrayList<AuditEntryResponse>(entries.size());
        for (AuditEntry e : entries) {
            result.add(toResponse(e));
        }
        return result;
    }

    private AuditEntryResponse toResponse(AuditEntry e) throws Exception {
        return new AuditEntryResponse(
            e.id, e.eventId, e.topic, e.eventType, e.aggregateId,
            mapper.readTree(e.rawPayload), e.receivedAt
        );
    }
}
