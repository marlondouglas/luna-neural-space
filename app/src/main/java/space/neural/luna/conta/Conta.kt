package space.neural.luna.conta

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.tasks.await
import space.neural.luna.BuildConfig

/**
 * Conta do motorista no Firebase Auth (provedor e-mail/senha).
 * Ao criar a conta, a conta anônima que o app já usava é VINCULADA (linkWithCredential): o uid continua o
 * mesmo e os ganhos já sincronizados em users/{uid} ficam na conta nova.
 */
object Conta {
    private fun auth(): FirebaseAuth? = if (BuildConfig.FIREBASE_CONFIGURED) runCatching { FirebaseAuth.getInstance() }.getOrNull() else null

    /** Usuário com conta de verdade (não anônimo), ou null. */
    fun usuario(): FirebaseUser? = auth()?.currentUser?.takeIf { !it.isAnonymous && it.email != null }

    fun logado(): Boolean = usuario() != null

    fun nome(): String = runCatching { usuario()?.displayName }.getOrNull().orEmpty()
    /** Primeiro nome do cadastro ("" sem conta). */
    fun primeiroNome(): String = nome().trim().split(Regex("\\s+")).firstOrNull().orEmpty().take(20)
    /** ", Ana" para saudações ("Boa tarde, Ana"); "" sem nome. */
    fun vocativo(): String = primeiroNome().let { if (it.isBlank()) "" else ", $it" }
    fun exibicao(): String = ContaLogica.exibir(usuario()?.email)

    class ErroConta(msg: String) : Exception(msg)

    suspend fun criar(nome: String, id: ContaLogica.Identificador, senha: String) {
        val a = auth() ?: throw ErroConta("Login indisponível nesta versão.")
        traduzir {
            val atual = a.currentUser
            val cred = EmailAuthProvider.getCredential(id.emailFirebase, senha)
            val user = if (atual != null && atual.isAnonymous) {
                atual.linkWithCredential(cred).await().user
            } else {
                a.createUserWithEmailAndPassword(id.emailFirebase, senha).await().user
            } ?: throw ErroConta("Não foi possível criar a conta.")
            user.updateProfile(userProfileChangeRequest { displayName = nome.trim().take(60) }).await()
            user.getIdToken(true).await() // token novo já como conta (não anônima)
        }
    }

    suspend fun entrar(id: ContaLogica.Identificador, senha: String) {
        val a = auth() ?: throw ErroConta("Login indisponível nesta versão.")
        traduzir { a.signInWithEmailAndPassword(id.emailFirebase, senha).await() }
    }

    suspend fun redefinirSenha(email: ContaLogica.Identificador.Email) {
        val a = auth() ?: throw ErroConta("Login indisponível nesta versão.")
        traduzir { a.sendPasswordResetEmail(email.emailFirebase).await() }
    }

    fun sair() { space.neural.luna.sync.RealtimeSync.desligar(); auth()?.signOut() }

    fun uid(): String? = usuario()?.uid

    /** ID token atual (renovado pelo Firebase quando perto de vencer). Chamar fora da thread principal. */
    suspend fun idToken(forcar: Boolean = false): String? =
        runCatching { usuario()?.getIdToken(forcar)?.await()?.token }.getOrNull()

    private suspend fun <T> traduzir(bloco: suspend () -> T): T = try {
        bloco()
    } catch (e: ErroConta) {
        throw e
    } catch (e: FirebaseAuthUserCollisionException) {
        throw ErroConta("Já existe uma conta com este e-mail ou celular. Toque em \"Entrar\".")
    } catch (e: FirebaseAuthWeakPasswordException) {
        throw ErroConta("Senha fraca. Use pelo menos ${ContaLogica.SENHA_MIN} caracteres.")
    } catch (e: FirebaseAuthInvalidUserException) {
        throw ErroConta("Conta não encontrada. Confira os dados ou crie uma conta.")
    } catch (e: FirebaseAuthInvalidCredentialsException) {
        throw ErroConta("E-mail/celular ou senha incorretos.")
    } catch (e: FirebaseNetworkException) {
        throw ErroConta("Sem internet. Conecte-se e tente de novo.")
    } catch (e: FirebaseTooManyRequestsException) {
        throw ErroConta("Muitas tentativas. Espere alguns minutos e tente de novo.")
    } catch (e: Exception) {
        val m = e.message.orEmpty()
        throw ErroConta(
            if (m.contains("OPERATION_NOT_ALLOWED") || m.contains("not enabled")) "Login por e-mail ainda não foi ativado no servidor. Fale com o suporte."
            else "Não foi possível concluir agora. Tente de novo."
        )
    }
}
