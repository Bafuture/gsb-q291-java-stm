package com.example.gsb.stm;

/** Base type for everything the STM runtime reports to callers. */
public class StmException extends RuntimeException {

    public StmException(String message) {
        super(message);
    }

    public StmException(String message, Throwable cause) {
        super(message, cause);
    }
}
