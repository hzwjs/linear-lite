package com.linearlite.server.service;

import com.linearlite.server.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionCredentialServiceTest {
    private final ExecutionCredentialService service = new ExecutionCredentialService();

    @Test
    void authenticatesCredentialOnlyForItsExecution() {
        String credential = service.issue(7L, "execution-1");

        assertEquals(7L, service.authenticate(credential, "execution-1"));
        assertEquals("execution-1", service.executionId(credential));
        assertThrows(UnauthorizedException.class,
                () -> service.authenticate(credential, "execution-2"));
    }

    @Test
    void replacingExecutionCredentialRevokesPreviousCredential() {
        String previous = service.issue(7L, "execution-1");
        String current = service.issue(7L, "execution-1");

        assertNotEquals(previous, current);
        assertThrows(UnauthorizedException.class, () -> service.authenticate(previous));
        assertEquals(7L, service.authenticate(current));
    }
}
