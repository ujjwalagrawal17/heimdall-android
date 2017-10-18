package pm.gnosis.heimdall.ui.multisig.details.info

import android.content.Context
import io.reactivex.Observable
import io.reactivex.functions.Function
import pm.gnosis.heimdall.common.di.ApplicationContext
import pm.gnosis.heimdall.common.util.mapToResult
import pm.gnosis.heimdall.data.repositories.MultisigRepository
import pm.gnosis.heimdall.data.repositories.model.MultisigWalletInfo
import pm.gnosis.heimdall.ui.exceptions.LocalizedException
import javax.inject.Inject

class MultisigInfoViewModel @Inject constructor(
        @ApplicationContext private val context: Context,
        private val multisigRepository: MultisigRepository
) : MultisigInfoContract() {

    private val errorHandler = LocalizedException.networkErrorHandlerBuilder(context)
            .build()

    private var cachedInfo: MultisigWalletInfo? = null

    private var address: String? = null

    override fun setup(address: String) {
        if (this.address == address) {
            return
        }
        this.address = address
        cachedInfo = null
    }

    override fun loadMultisigInfo(ignoreCache: Boolean) =
            (fromCache(ignoreCache) ?:
                    multisigRepository.loadMultisigWalletInfo(address!!)
                            .onErrorResumeNext(Function { errorHandler.observable(it) })
                            .doOnNext { cachedInfo = it })
                    .mapToResult()

    private fun fromCache(ignoreCache: Boolean): Observable<MultisigWalletInfo>? {
        if (!ignoreCache) {
            return cachedInfo?.let { Observable.just(it) }
        }
        return null
    }
}