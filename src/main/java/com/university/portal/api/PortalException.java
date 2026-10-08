package com.university.portal.api;

import org.springframework.http.HttpStatus;

public class PortalException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public PortalException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
