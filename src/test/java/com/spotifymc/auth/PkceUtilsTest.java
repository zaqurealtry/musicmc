package com.spotifymc.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PkceUtilsTest {

    @Test
    public void testCodeVerifierLength() {
        String verifier = PkceUtils.generateCodeVerifier(64);
        assertNotNull(verifier);
        assertEquals(64, verifier.length());

        String defaultVerifier = PkceUtils.generateCodeVerifier();
        assertNotNull(defaultVerifier);
        assertEquals(64, defaultVerifier.length());
    }

    @Test
    public void testCodeVerifierCharacters() {
        String verifier = PkceUtils.generateCodeVerifier(128);
        assertTrue(verifier.matches("^[A-Za-z0-9\\-._~]+$"), "Verifier must only contain RFC 7636 unreserved characters");
    }

    @Test
    public void testRfc7636AppendixBTestVector() {
        // Official RFC 7636 Appendix B test vector
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

        String computedChallenge = PkceUtils.generateCodeChallenge(verifier);
        assertEquals(expectedChallenge, computedChallenge, "Code challenge must match RFC 7636 Appendix B standard test vector");
    }

    @Test
    public void testStateGeneration() {
        String state1 = PkceUtils.generateState();
        String state2 = PkceUtils.generateState();
        assertNotNull(state1);
        assertNotNull(state2);
        assertNotEquals(state1, state2, "Random state should be unique");
    }
}
