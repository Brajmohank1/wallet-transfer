package com.brajmohan.wallettransfer.exception;

// An idempotency key was reused with a request that hashes differently
// from the one originally stored under it.
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
