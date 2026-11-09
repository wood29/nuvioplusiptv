package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupCountryTest {
    @Test fun readsTheCountryPrefix() {
        assertEquals("UK", GroupCountry.of("UK | Sports"))
        assertEquals("DE", GroupCountry.of("|DE| Nachrichten"))
        assertEquals("AT", GroupCountry.of("AT: ORF"))
        assertEquals("FR", GroupCountry.of("[FR] Cinéma"))
        assertEquals("US", GroupCountry.of("us - Locals"))
        assertEquals("EXYU", GroupCountry.of("EXYU ▎ Sport"))
    }

    @Test fun aRegionPrefixGivesWayToTheCountry() {
        assertEquals("AT", GroupCountry.of("EU | AUSTRIA"))
        assertEquals("UK", GroupCountry.of("EU | UK CINEMA"))
        assertEquals("DE", GroupCountry.of("EU | GERMANY PPV"))
        assertEquals("CH", GroupCountry.of("EU | SWITZERLAND BLUE SPORT [FR]"))
        assertEquals("DE", GroupCountry.of("GERMANY | Kino"))
    }

    @Test fun noCountryWhenThereIsNoPrefix() {
        assertNull(GroupCountry.of("Al Jazeera"))
        assertNull(GroupCountry.of("Sport"))
        assertNull(GroupCountry.of("HD | Sport"))
        assertNull(GroupCountry.of("VIP: Events"))
        assertNull(GroupCountry.of(""))
    }
}
