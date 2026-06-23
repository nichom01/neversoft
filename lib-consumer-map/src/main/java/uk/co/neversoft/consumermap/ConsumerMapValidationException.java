package uk.co.neversoft.consumermap;

/** Thrown when consumer-map.yml fails schema or semantic validation. */
public class ConsumerMapValidationException extends Exception {
    public ConsumerMapValidationException(String message) {
        super(message);
    }
}
