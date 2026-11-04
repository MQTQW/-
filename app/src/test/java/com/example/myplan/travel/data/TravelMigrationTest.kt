package com.example.myplan.travel.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TravelMigrationTest {
    @Test fun oldTripAndStopSurviveAndEvidenceSurvivesBasicEditing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "travel-migration-${UUID.randomUUID()}.db"
        val schemaPath = "schemas/com.example.myplan.travel.data.TravelDatabase/1.json"
        val schemaFile = listOf(File(schemaPath), File("app/$schemaPath")).first { it.isFile }
        val entities = JSONObject(schemaFile.readText()).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }, null).use { old ->
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            old.execSQL("INSERT INTO trips VALUES (1,'旧行程','杭州','2026-11-08',1,'Asia/Shanghai','步行','适中','保留备注',1,1)")
            old.execSQL("INSERT INTO trip_stops VALUES (1,1,0,0,'西湖','西湖',540,60,'旧节点',1)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, TravelDatabase::class.java, name)
            .addMigrations(TravelDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val repo = TravelRepository(db)
            val old = repo.getTrip(1)!!
            assertEquals("保留备注", old.trip.notes)
            assertNull(old.trip.liveEvidenceJson)
            assertTrue(old.stops.single().isCompleted)
            val evidence = "{\"status\":\"ready\",\"warnings\":[\"交通待核对\"]}"
            val newId = repo.saveTrip(old.trip.copy(id = 0, title = "联网行程", liveEvidenceJson = evidence))
            val saved = repo.getTrip(newId)!!.trip
            repo.saveTrip(saved.copy(title = "手动改名", liveEvidenceJson = null))
            assertEquals(evidence, repo.getTrip(newId)!!.trip.liveEvidenceJson)
            assertEquals("旧行程", repo.getTrip(1)!!.trip.title)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
