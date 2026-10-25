package com.m3.veil;

import com.m3.veil.utils.AnonymousAuthManager;

import org.junit.Test;

import static org.junit.Assert.*;

public class AnonymousAuthTest {

    @Test
    public void generateRecoveryPhrase_generatesTwelveWords() {
        String phrase = AnonymousAuthManager.generateRecoveryPhrase();
        assertNotNull(phrase);
        String[] words = phrase.split(" ");
        assertEquals(12, words.length);
        for (String word : words) {
            assertFalse(word.trim().isEmpty());
        }
    }

    @Test
    public void derivation_isDeterministic() {
        String phrase = "abandon ability able about above absent absorb abstract absurd abuse access accident";
        String email1 = AnonymousAuthManager.getEmailFromPhrase(phrase);
        String email2 = AnonymousAuthManager.getEmailFromPhrase(phrase);
        assertEquals(email1, email2);
        assertTrue(email1.endsWith("@veil.local"));

        String pass1 = AnonymousAuthManager.getPasswordFromPhrase(phrase);
        String pass2 = AnonymousAuthManager.getPasswordFromPhrase(phrase);
        assertEquals(pass1, pass2);
        assertEquals(20, pass1.length());
    }
}
