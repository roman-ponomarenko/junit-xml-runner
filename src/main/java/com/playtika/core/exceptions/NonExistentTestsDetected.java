package com.playtika.core.exceptions;

public class NonExistentTestsDetected extends RuntimeException {
    public NonExistentTestsDetected(String message) {
        super(message);
    }
}
