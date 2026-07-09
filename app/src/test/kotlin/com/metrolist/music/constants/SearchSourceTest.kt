package com.metrolist.music.constants

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSourceTest {
    @Test
    fun fromPreferenceMigratesLegacyValues() {
        assertEquals(SearchSource.LIBRARY, SearchSource.fromPreference("LOCAL"))
        assertEquals(SearchSource.CHINA, SearchSource.fromPreference("ONLINE"))
    }

    @Test
    fun fromPreferenceKeepsCurrentValues() {
        assertEquals(SearchSource.CHINA, SearchSource.fromPreference("CHINA"))
        assertEquals(SearchSource.CHINA_SONGLIST, SearchSource.fromPreference("CHINA_SONGLIST"))
        assertEquals(SearchSource.LIBRARY, SearchSource.fromPreference("LIBRARY"))
        assertEquals(SearchSource.ADVANCED, SearchSource.fromPreference("ADVANCED"))
    }
}
