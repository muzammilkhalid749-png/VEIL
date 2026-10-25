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

    @Test
    public void derivation_normalizesPastedPhrases() {
        String canonical = "abandon ability able about above absent absorb abstract absurd abuse access accident";
        String pastedWithNewlinesAndCaps = "Abandon\nAbility\nAble\nAbout\nAbove\nAbsent\nAbsorb\nAbstract\nAbsurd\nAbuse\nAccess\nAccident";
        String pastedWithNumbersAndSpaces = "1. abandon   2. ability  3. able   4. about  5. above  6. absent  7. absorb  8. abstract  9. absurd  10. abuse  11. access  12. accident";

        assertEquals(AnonymousAuthManager.getEmailFromPhrase(canonical), AnonymousAuthManager.getEmailFromPhrase(pastedWithNewlinesAndCaps));
        assertEquals(AnonymousAuthManager.getEmailFromPhrase(canonical), AnonymousAuthManager.getEmailFromPhrase(pastedWithNumbersAndSpaces));

        assertEquals(AnonymousAuthManager.getPasswordFromPhrase(canonical), AnonymousAuthManager.getPasswordFromPhrase(pastedWithNewlinesAndCaps));
        assertEquals(AnonymousAuthManager.getPasswordFromPhrase(canonical), AnonymousAuthManager.getPasswordFromPhrase(pastedWithNumbersAndSpaces));
    }
}
