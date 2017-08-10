package pm.gnosis.android.app.wallet.ui.multisig

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.schedulers.Schedulers
import pm.gnosis.android.app.wallet.data.db.GnosisAuthenticatorDb
import pm.gnosis.android.app.wallet.data.db.MultisigWallet
import pm.gnosis.android.app.wallet.di.ForView
import javax.inject.Inject

@ForView
class MultisigPresenter @Inject constructor(private val gnosisAuthenticatorDb: GnosisAuthenticatorDb) {
    fun observeMultisigList(): Flowable<List<MultisigWallet>> {
        return gnosisAuthenticatorDb.multisigWalletDao().observeMultisigWallets().subscribeOn(Schedulers.io())
    }

    fun addMultisigWallet(name: String = "", address: String) = Completable.fromCallable {
        val multisigWallet = MultisigWallet()
        multisigWallet.name = name
        multisigWallet.address = address
        gnosisAuthenticatorDb.multisigWalletDao().insertMultisigWallet(multisigWallet)
    }.subscribeOn(Schedulers.io())

    fun removeMultisigWallet(multisigWallet: MultisigWallet) = Completable.fromCallable {
        gnosisAuthenticatorDb.multisigWalletDao().removeMultisigWallet(multisigWallet)
    }.subscribeOn(Schedulers.io())

    fun updateMultisigWalletName(address: String, newName: String) = Completable.fromCallable {
        val multisigWallet = MultisigWallet()
        multisigWallet.name = newName
        multisigWallet.address = address
        gnosisAuthenticatorDb.multisigWalletDao().updateMultisigWallet(multisigWallet)
    }.subscribeOn(Schedulers.io())
}