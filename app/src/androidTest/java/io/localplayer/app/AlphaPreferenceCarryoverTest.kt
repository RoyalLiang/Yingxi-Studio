package io.localplayer.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AlphaPreferenceCarryoverTest {
    @Test fun alphaSpeedValuesSurviveCutoverAndReopeningThePreferenceStore(){
        for(value in listOf(.25f,.5f,1f,2f,4f)){
            val context=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext)
            try{
                assertTrue(context.getSharedPreferences("playback-settings-v1",0).edit().putFloat("speed",value).commit())
                assertEquals("Committed",MediaDataMigration.session(context).migrate().state)
                assertEquals(value,PlaybackPreferences(context).speed)
                PlaybackPreferences(context).speed=value
                assertEquals(value,PlaybackPreferences(context).speed)
                assertEquals(value,context.getSharedPreferences("playback-settings-v1",0).getFloat("speed",-1f))
            }finally{context.dispose()}
        }
    }

    @Test fun carriedDiaryIdsStaySelectedThroughTheMigratingPreferenceStore(){
        val context=IsolatedMediaContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try{
            assertEquals("Committed",MediaDataMigration.session(context).migrate().state)
            val preferences=MigratingPreferences.open(context,"station-experience-v1")
            val ids=listOf("diary_station_logbook_cloth_20261026","diary_station_chair_cloth_20261026","diary_station_trade_clips_20261026",
                "diary_salt_blank_tally_20261026","diary_station_index_cord_20261026","diary_station_frequency_log_20261026",
                "quote_axi_alpha_carryover_20261026","quote_chideng_alpha_carryover_20261026","quote_mitu_alpha_carryover_20261026")
            for(id in ids){
                assertTrue(preferences.edit().putString("diary-line",id).putLong("diary-at",10_000).commit())
                val selected=StationPreferences(context).diary(now=10_001)
                assertEquals(id,selected.id)
                assertTrue(selected.text.zh.isNotBlank()&&selected.text.en.isNotBlank())
            }
        }finally{context.dispose()}
    }
}
