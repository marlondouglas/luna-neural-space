package space.neural.luna.vehicle

import android.content.Context
import space.neural.luna.automotive.AutomotiveProfile

object VehicleProviderFactory {
    fun create(context: Context, manualOptIn: Boolean = false): VehicleProvider = createForProfile(AutomotiveProfile.current(context, manualOptIn)) {
        AndroidCarVehicleProvider(context.applicationContext)
    }
    /** A known head unit/manual choice permits attempting the public API, never grants a property. */
    internal fun createForProfile(profile: AutomotiveProfile, publicSdkProvider: () -> VehicleProvider): VehicleProvider {
        if (!profile.automotive) return UnavailableVehicleProvider("Dispositivo sem perfil automotivo identificado ou escolhido. Nenhuma API veicular foi acessada.")
        return try { publicSdkProvider() }
        catch (error: LinkageError) { UnavailableVehicleProvider("Biblioteca Android Car não disponível neste firmware") }
        catch (error: SecurityException) { UnavailableVehicleProvider("Acesso Android Car não autorizado pelo sistema") }
    }
}
