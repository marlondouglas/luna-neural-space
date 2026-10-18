package space.neural.luna.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Banco local: fonte da verdade do app. Tudo funciona offline a partir daqui. */
@Database(
    entities = [Turno::class, Ganho::class, Gasto::class, Meta::class, Anotacao::class],
    version = 1,
    exportSchema = true,
)
abstract class LunaDatabase : RoomDatabase() {
    abstract fun turnoDao(): TurnoDao
    abstract fun turnoQueries(): TurnoQueries
    abstract fun ganhoDao(): GanhoDao
    abstract fun gastoDao(): GastoDao
    abstract fun metaDao(): MetaDao
    abstract fun anotacaoDao(): AnotacaoDao

    companion object {
        @Volatile private var instance: LunaDatabase? = null

        fun get(context: Context): LunaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LunaDatabase::class.java,
                    "luna.db",
                ).build().also { instance = it }
            }
    }
}
