package space.neural.luna.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Banco local: fonte da verdade do app. Tudo funciona offline a partir daqui. */
@Database(
    entities = [Turno::class, Ganho::class, Gasto::class, Meta::class, Anotacao::class, Planejamento::class, ContaFixa::class, Limite::class, MensagemChat::class, AuditoriaFinanceira::class],
    version = 4,
    exportSchema = true,
    // v2: coluna metas.tipo (DIA/SEMANA/MES), com padrão DIA para metas antigas.
    // v3: turnos.numero (1–4), gastos.limiteId e as tabelas planejamento, contas_fixas, limites e mensagens_chat.
    // Migrações automáticas só acrescentam colunas/tabelas: os dados existentes ficam.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class LunaDatabase : RoomDatabase() {
    abstract fun auditoriaFinanceiraDao(): AuditoriaFinanceiraDao
    abstract fun turnoDao(): TurnoDao
    abstract fun turnoQueries(): TurnoQueries
    abstract fun syncQueries(): SyncQueries
    abstract fun ganhoDao(): GanhoDao
    abstract fun gastoDao(): GastoDao
    abstract fun metaDao(): MetaDao
    abstract fun anotacaoDao(): AnotacaoDao
    abstract fun planejamentoDao(): PlanejamentoDao
    abstract fun contaFixaDao(): ContaFixaDao
    abstract fun limiteDao(): LimiteDao
    abstract fun chatDao(): ChatDao

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
