package org.lovetropics.multimedia;

public class DecoderException extends Exception {
    public DecoderException() {
    }

    public DecoderException(final String message) {
        super(message);
    }

    public DecoderException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public DecoderException(final Throwable cause) {
        super(cause);
    }
}
