package com.example.gpsengine.error;

/**
 * Thrown when credentials fail or authentication is missing/invalid.
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}

