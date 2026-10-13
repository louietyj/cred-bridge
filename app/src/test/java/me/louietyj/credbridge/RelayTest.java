package me.louietyj.credbridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RelayTest {

    private static boolean plain(String site) {
        return new Relay.Request("r", site, "why", "https://x.hostc.app/t", 0, "opened").hasPlainSite();
    }

    @Test
    public void plainDomainsAreAccepted() {
        assertTrue(plain("chase.com"));
        assertTrue(plain("secure.chase.com"));
        assertTrue(plain("17track.net"));
        assertTrue(plain("my-bank.co.uk"));
        // Punycode passes, but shows as xn--, which does not look like the real site.
        assertTrue(plain("xn--chse-xqa.com"));
    }

    @Test
    public void anythingElseIsRefused() {
        assertFalse(plain(null));
        assertFalse(plain("Chase.com"));
        assertFalse(plain("chаse.com"));  // Cyrillic a
        assertFalse(plain("https://chase.com"));
        assertFalse(plain("chase.com/login"));
        assertFalse(plain("chase.com "));
        assertFalse(plain("localhost"));
        assertFalse(plain("1.2.3.4"));
        assertFalse(plain("-chase.com"));
    }
}
