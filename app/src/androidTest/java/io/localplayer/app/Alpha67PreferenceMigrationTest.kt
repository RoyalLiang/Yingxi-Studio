package io.localplayer.app

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class Alpha67PreferenceMigrationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun isolated(action:(IsolatedMediaContext)->Unit){
        val context=IsolatedMediaContext(instrumentation.targetContext)
        try{action(context)}finally{context.dispose()}
    }

    @Test fun currentAppearanceAndReadSettingsMigrateWithoutChangingOriginals()=isolated{context->
        val appearance=context.getSharedPreferences("library-appearance",0)
        val cloud=context.getSharedPreferences("cloud-playback",0)
        appearance.edit().putString("mode",LibraryAppearance.NIGHT.name).commit()
        cloud.edit().putInt("workers",2).putBoolean("segment-cache",false).putBoolean("next-episode",false).commit()
        val beforeAppearance=appearance.all.toMap();val beforeCloud=cloud.all.toMap()
        assertEquals("Committed",MediaDataMigration.session(context).migrate().state)
        assertEquals(LibraryAppearance.NIGHT.name,MigratingPreferences.open(context,"library-appearance").getString("mode",null))
        assertEquals(LibraryAppearance.NIGHT,AppearancePreferences(context).mode)
        assertEquals(2,CloudReadPreferences(context).workers)
        assertFalse(CloudReadPreferences(context).cacheEnabled)
        assertFalse(CloudReadPreferences(context).nextEpisode)
        CloudReadPreferences(context).workers=3
        assertEquals(3,CloudReadPreferences(context).workers)
        assertEquals(beforeAppearance,appearance.all)
        assertEquals(beforeCloud,cloud.all)
    }

    @Test fun separatelyOpenedAppearanceSettingsShareNotifications()=isolated{context->
        assertEquals("Committed",MediaDataMigration.session(context).migrate().state)
        val one=MigratingPreferences.open(context,"library-appearance")
        val two=MigratingPreferences.open(context,"library-appearance")
        assertSame(one,two)
        var notified=0
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="mode")notified++}
        one.registerOnSharedPreferenceChangeListener(listener)
        try{
            instrumentation.runOnMainSync{two.edit().putString("mode",LibraryAppearance.NIGHT.name).commit()}
            assertEquals(1,notified)
            assertEquals(LibraryAppearance.NIGHT.name,one.getString("mode",null))
        }finally{one.unregisterOnSharedPreferenceChangeListener(listener)}
    }

    @Test fun olderLocalBetaImportsNewDomainsOnlyOnce()=isolated{context->
        val original=context.getSharedPreferences("library-appearance",0)
        original.edit().putString("mode",LibraryAppearance.NIGHT.name).commit()
        assertEquals("Committed",MediaDataMigration.session(context).migrate().state)
        SQLiteDatabase.openDatabase(context.getDatabasePath(MEDIA_DATABASE).path,null,SQLiteDatabase.OPEN_READWRITE).use{db->
            db.delete("preferences","scope=?",arrayOf("library-appearance"))
            db.delete("metadata","name=?",arrayOf("preference-scope:library-appearance"))
        }
        val bridge=MigratingPreferences.open(context,"library-appearance")
        assertEquals(LibraryAppearance.NIGHT.name,bridge.getString("mode",null))
        bridge.edit().remove("mode").commit()
        assertFalse(MigratingPreferences.open(context,"library-appearance").contains("mode"))
        assertEquals(LibraryAppearance.NIGHT.name,original.getString("mode",null))
    }
}
