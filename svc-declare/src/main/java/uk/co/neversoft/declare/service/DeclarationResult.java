package uk.co.neversoft.declare.service;

import java.util.UUID;

public record DeclarationResult(UUID declarationId, boolean created) {}
