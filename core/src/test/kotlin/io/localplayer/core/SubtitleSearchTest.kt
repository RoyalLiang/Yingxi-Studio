package io.localplayer.core

import org.junit.Assert.*
import org.junit.Test

class SubtitleSearchTest {
    @Test fun encodesOnlyTheFilmQueryForPathAndQueryTemplates(){
        assertEquals("https://example.org/search/%E6%98%A0%E6%B1%90%20%26%202026",subtitleSearchUrl(" https://example.org/search/{query} ","映汐 & 2026"))
        assertEquals("https://example.org/?q=a%2Fb%3Fx%3D1&lang=zh",subtitleSearchUrl("https://example.org/?q={query}&lang=zh","a/b?x=1"))
    }
    @Test fun rejectsUnsafeOrAmbiguousAddresses(){
        listOf("javascript:{query}","file:///{query}","https://"+"u:p@example.org/{query}","https://example.org/","https://example.org/{query}/{query}","https://example.org/{query}/{other}").forEach{value->
            assertTrue(value,runCatching{subtitleSearchUrl(value,"Film")}.isFailure)
        }
        assertTrue(runCatching{subtitleSearchUrl("https://example.org/{query}"," ")}.isFailure)
    }
}
