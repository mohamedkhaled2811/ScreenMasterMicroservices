package com.gr74.catalog.exception;

/**
 * Base class for deliberate service errors. Carries a {@link CatalogErrorCode}.
 */
public class CatalogException extends RuntimeException {

    private final transient CatalogErrorCode errorCode;

    public CatalogException(CatalogErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CatalogException(CatalogErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public CatalogErrorCode errorCode() {
        return errorCode;
    }
}
