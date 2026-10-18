package space.neural.luna.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import space.neural.luna.BuildConfig
import space.neural.luna.data.local.LunaDatabase

/** Monta o [SyncEngine] da conta atual (ou null sem Firebase / sem usuário). */
object Nuvem {
    /** Para testes: substitui a nuvem real (ex.: servidor em memória). */
    @Volatile var fabricaTeste: ((Context) -> SyncEngine?)? = null

    fun firebaseOk(ctx: Context): Boolean {
        if (!BuildConfig.FIREBASE_CONFIGURED) return false
        return FirebaseApp.getApps(ctx).isNotEmpty() || FirebaseApp.initializeApp(ctx) != null
    }

    /** uid da conta atual; anônimo só quando a licença (conta obrigatória) está desligada. */
    fun uid(ctx: Context): String? {
        if (!firebaseOk(ctx)) return null
        val u = FirebaseAuth.getInstance().currentUser ?: return null
        if (space.neural.luna.licenca.Licenca.ativa && (u.isAnonymous || u.email == null)) return null
        return u.uid
    }

    fun engine(ctx: Context): SyncEngine? {
        fabricaTeste?.let { return it(ctx) }
        val app = ctx.applicationContext
        val uid = uid(app) ?: return null
        return SyncEngine(app, LunaDatabase.get(app), FirestoreRemote(FirebaseFirestore.getInstance(), uid), uid, Aparelho.id(app))
    }

    fun temInternet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
