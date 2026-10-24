package space.neural.luna.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.neural.luna.data.local.LunaDatabase
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationAuditTest {
    @Test fun v3ToV4PreservesFinancialRows() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val schema = listOf(File("schemas/space.neural.luna.data.local.LunaDatabase/3.json"),
            File("app/schemas/space.neural.luna.data.local.LunaDatabase/3.json")).first { it.exists() }
        val json = JSONObject(schema.readText()).getJSONObject("database")
        val name = "migration-audit-${System.nanoTime()}.db"
        app.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = json.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = json.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO gastos(id,data,categoria,valorCentavos,updatedAt,dirty,deleted) VALUES ('old-expense','2026-10-22','OUTRO',10000,1000,1,0)")
            old.version = 3
        }
        val migrated = Room.databaseBuilder(app, LunaDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals(10000L, migrated.gastoDao().byId("old-expense")!!.valorCentavos)
            assertEquals(4, migrated.openHelper.writableDatabase.version)
            assertTrue(migrated.auditoriaFinanceiraDao().historico("old-expense").isEmpty())
        } finally { migrated.close(); app.deleteDatabase(name) }
    }
}
