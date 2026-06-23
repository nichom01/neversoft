package uk.co.neversoft.consumermap;

import java.util.List;

/** All declared consumers for a single Kafka topic. */
public record EventEntry(List<ConsumerRegistration> consumers) {}
