package pm.gnosis.heimdall.ui.transactions

import android.app.Activity
import android.arch.lifecycle.LifecycleOwner
import android.content.Context
import android.content.Intent
import android.support.v7.widget.Toolbar
import android.view.View
import com.jakewharton.rxbinding2.view.clicks
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.android.synthetic.main.layout_address_item.view.*
import kotlinx.android.synthetic.main.layout_submit_transaction.*
import kotlinx.android.synthetic.main.layout_transaction_confirmation_item.view.*
import kotlinx.android.synthetic.main.merge_transaction_details_container.*
import pm.gnosis.heimdall.HeimdallApplication
import pm.gnosis.heimdall.R
import pm.gnosis.heimdall.common.di.components.DaggerViewComponent
import pm.gnosis.heimdall.common.di.modules.ViewModule
import pm.gnosis.heimdall.data.repositories.models.Safe
import pm.gnosis.heimdall.reporting.Event
import pm.gnosis.heimdall.reporting.ScreenId
import pm.gnosis.heimdall.ui.addressbook.helpers.AddressInfoViewHolder
import pm.gnosis.heimdall.ui.base.InflatingViewProvider
import pm.gnosis.heimdall.ui.dialogs.share.RequestSignatureDialog
import pm.gnosis.heimdall.ui.safe.details.SafeDetailsActivity
import pm.gnosis.heimdall.ui.security.unlock.UnlockActivity
import pm.gnosis.heimdall.utils.*
import pm.gnosis.models.AddressBookEntry
import pm.gnosis.models.Transaction
import pm.gnosis.svalinn.common.utils.*
import timber.log.Timber
import java.math.BigInteger
import javax.inject.Provider

class SubmitTransactionActivity : ViewTransactionActivity() {

    private val viewProvider by lazy {
        InflatingViewProvider(
            layoutInflater,
            layout_submit_transaction_confirmations_addresses,
            R.layout.layout_transaction_confirmation_item
        )
    }

    private var cachedTransactionData: CachedTransactionData? = null
    private var credentialsConfirmed: Boolean = false
    private var pendingSignature: String? = null

    private val transactionInfoTransformer: ObservableTransformer<Pair<BigInteger?, Result<Transaction>>, Result<ViewTransactionContract.Info>> =
        ObservableTransformer { up: Observable<Pair<BigInteger?, Result<Transaction>>> ->
            up
                .doOnNext { setUnknownTransactionInfo() }
                .checkedFlatMap { safeAddress, transaction ->
                    viewModel.loadExecuteInfo(safeAddress, transaction)
                }
        }

    private val displayTransactionInformation: ObservableTransformer<Result<ViewTransactionContract.Info>, Any> =
        ObservableTransformer {
            it
                .observeOn(AndroidSchedulers.mainThread())
                // Update displayed information
                .doOnNextForResult(::updateInfo, ::handleInputError)
                // Pass on data for submit
                .publish {
                    Observable.merge(
                        it.compose(submitTransformer),
                        it.compose(submitToExternalWalletTransformer)
                    )
                }
        }

    private val submitTransformer: ObservableTransformer<Result<ViewTransactionContract.Info>, *> =
        ObservableTransformer { up: Observable<Result<ViewTransactionContract.Info>> ->
            // We combine the data with the submit button events
            up.switchMap { safeTransactionInfo ->
                layout_submit_transaction_submit_button.clicks().map { safeTransactionInfo }
            }
                .doOnNext { info ->
                    (info as? DataResult)?.let {
                        cachedTransactionData =
                                CachedTransactionData(it.data.selectedSafe, it.data.status.transaction)
                        startActivityForResult(UnlockActivity.createConfirmIntent(this), REQUEST_CODE_CONFIRM_CREDENTIALS)
                    } ?: run {
                        cachedTransactionData = null
                    }
                }
        }

    private val submitToExternalWalletTransformer: ObservableTransformer<Result<ViewTransactionContract.Info>, *> =
        ObservableTransformer { up: Observable<Result<ViewTransactionContract.Info>> ->
            up.switchMap { safeTransactionInfo -> layout_submit_transaction_external.clicks().map { safeTransactionInfo } }
                .doOnNext { info ->
                    (info as? DataResult)?.let {
                        cachedTransactionData =
                                CachedTransactionData(it.data.selectedSafe, it.data.status.transaction)
                    } ?: run {
                        cachedTransactionData = null
                    }
                }
                .flatMapSingle {
                    (it as? DataResult)?.let {
                        viewModel.loadExecutableTransaction(it.data.selectedSafe, it.data.status.transaction)
                    } ?: throw IllegalStateException()
                }
                .doOnNext {
                    startActivityWithTransaction(it, onActivityNotFound = {
                        snackbar(layout_submit_transaction_coordinator, R.string.no_external_wallets)
                        Timber.w("External wallet not found")
                    })
                }
                .mapToResult()
        }

    private val requestSignaturesQR: ObservableTransformer<Result<ViewTransactionContract.Info>, Any> =
        ObservableTransformer {
            it.observeOn(AndroidSchedulers.mainThread()).switchMap { info ->
                layout_submit_transaction_scan_signature_qr_button.clicks().map { info }
                    .doOnNextForResult({
                        RequestSignatureDialog
                            .create(it.status.transactionHash, it.status.transaction, it.selectedSafe)
                            .show(supportFragmentManager, null)
                    })
            }
        }

    private val requestSignaturesPush: ObservableTransformer<Result<ViewTransactionContract.Info>, Result<Unit>> =
        ObservableTransformer {
            it.observeOn(AndroidSchedulers.mainThread()).switchMap { info ->
                layout_submit_transaction_send_signature_push_button.clicks().map { info }
            }
                .flatMapResult(::requestSignatureViaPush)
                .doOnNextForResult(
                    onNext = {
                        snackbar(layout_submit_transaction_send_signature_push_button, R.string.signature_request_sent)
                    },
                    onError = {
                        errorSnackbar(layout_submit_transaction_send_signature_push_button, it)
                    }
                )
        }

    private val signaturePushes: ObservableTransformer<Result<ViewTransactionContract.Info>, Any> =
        ObservableTransformer {
            it.switchMap { info ->
                (info as? DataResult)?.let {
                    viewModel.observeSignaturePushes(it.data.selectedSafe, it.data.status.transaction)
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnNextForResult(onError = { errorSnackbar(layout_submit_transaction_send_signature_push_button, it) })
                } ?: Observable.empty()
            }
        }

    override fun transactionDataTransformer(): ObservableTransformer<Pair<BigInteger?, Result<Transaction>>, Any> =
        ObservableTransformer {
            // Load execution information (hash, transaction with correct nonce, owner info)
            it.compose(transactionInfoTransformer).publish {
                // Split execution information stream into two sub-streams
                Observable.merge(
                    // Combine execution information and price data to update the displayed information
                    it.compose(displayTransactionInformation),
                    // Use execution information to allow adding signatures
                    it.compose(requestSignaturesQR),
                    it.compose(requestSignaturesPush),
                    // Use execution information and observe signatures
                    it.compose(signaturePushes)
                )
            }
        }

    override fun screenId() = ScreenId.SUBMIT_TRANSACTION

    override fun layout() = R.layout.layout_submit_transaction

    override fun toolbar(): Toolbar = layout_submit_transaction_toolbar

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        credentialsConfirmed = requestCode == REQUEST_CODE_CONFIRM_CREDENTIALS && resultCode == Activity.RESULT_OK
        handleTransactionHashResult(requestCode, resultCode, data, {
            cachedTransactionData?.let { cachedTransactionData ->
                disposables += viewModel.addLocalTransaction(cachedTransactionData.safeAddress, cachedTransactionData.transaction, it)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSubscribe {
                        layout_submit_transaction_submit_button.isEnabled = false
                        layout_submit_transaction_external.isEnabled = false
                    }
                    .doFinally {
                        layout_submit_transaction_submit_button.isEnabled = true
                        layout_submit_transaction_external.isEnabled = true
                    }
                    .subscribeBy(onSuccess = { startSafeTransactionsActivity(cachedTransactionData.safeAddress) },
                        onError = { Timber.e(it); startSafeTransactionsActivity(cachedTransactionData.safeAddress) })
            }
        })
        handleQrCodeActivityResult(requestCode, resultCode, data, {
            pendingSignature = it
        }, {
            pendingSignature = null
        })
    }

    private fun startSafeTransactionsActivity(safeAddress: BigInteger) {
        startActivity(
            SafeDetailsActivity.createIntent(
                this,
                Safe(safeAddress),
                R.string.tab_title_transactions
            ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onStart() {
        super.onStart()
        setUnknownTransactionInfo()
        cachedTransactionData?.let {
            if (credentialsConfirmed) {
                disposables += submitTransaction(it.safeAddress, it.transaction)
                    .subscribeForResult({
                        eventTracker.submit(Event.SubmittedTransaction())
                        startActivity(
                            SafeDetailsActivity.createIntent(
                                this,
                                Safe(it),
                                R.string.tab_title_transactions
                            ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    }, {
                        if (it is IllegalStateException) {
                            layout_submit_transaction_buy_credits_button.visible(true)
                            layout_submit_transaction_submit_button.visible(false)
                            snackbar(merge_transaction_details_container, R.string.error_not_enough_credits)
                        } else {
                            showErrorSnackbar(it)
                        }
                    })
            } else {
                snackbar(layout_submit_transaction_submit_button, R.string.please_confirm_credentials)
            }
        }
        cachedTransactionData = null
        credentialsConfirmed = false
        pendingSignature?.let {
            disposables += viewModel.addSignature(it)
                .subscribeBy({
                    Timber.e(it)
                    errorSnackbar(layout_submit_transaction_submit_button, it)
                })
        }
        pendingSignature = null

        layout_submit_transaction_buy_credits_button.visible(false)
        layout_submit_transaction_submit_button.visible(false)
        disposables += viewModel.observeHasCredits()
            .subscribeBy(onNext = {
                layout_submit_transaction_buy_credits_button.visible(!it)
                layout_submit_transaction_submit_button.visible(it)
            }, onError = Timber::e)

        disposables += layout_submit_transaction_buy_credits_button.clicks()
            .observeOn(AndroidSchedulers.mainThread())
            .flatMapSingle {
                viewModel.buyCredit(this).mapToResult()
            }
            .subscribeBy(onError = Timber::e)
    }

    override fun fragmentRegistered() {
        layout_submit_transaction_progress_bar.visibility = View.GONE
    }

    private fun requestSignatureViaPush(info: ViewTransactionContract.Info) =
        viewModel.sendSignaturePush(info)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                layout_submit_transaction_send_signature_push_button.isEnabled = false
                layout_submit_transaction_send_signature_push_progress.visible(true)
            }
            .doAfterTerminate {
                layout_submit_transaction_send_signature_push_button.isEnabled = true
                layout_submit_transaction_send_signature_push_progress.visible(false)
            }

    private fun submitTransaction(safe: BigInteger, transaction: Transaction) =
        viewModel.submitTransaction(safe, transaction)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe { submittingTransaction(true) }
            .doAfterTerminate { submittingTransaction(false) }
            .toObservable()

    private fun submittingTransaction(loading: Boolean) {
        layout_submit_transaction_progress_bar.visible(loading)
        layout_submit_transaction_submit_button.isEnabled = !loading
        transactionInputEnabled(!loading)
    }

    private fun updateInfo(info: ViewTransactionContract.Info) {
        val availableConfirmations = (if (info.status.isOwner) 1 else 0) + info.signatures.size
        val requiredConfirmationsAvailable = availableConfirmations >= info.status.requiredConfirmation
        info.status.let {
            val leftConfirmations = Math.max(0, it.requiredConfirmation - availableConfirmations)

            if (leftConfirmations > 0) {
                layout_submit_transaction_confirmations_hint_text.setFormattedText(
                    R.string.confirm_transaction_hint,
                    "required_confirmations" to leftConfirmations.toString()
                )
            } else {
                layout_submit_transaction_confirmations_hint_text.setText(R.string.submit_transaction_hint)
            }
            layout_submit_transaction_confirmations.text =
                    getString(R.string.x_of_x_confirmations, availableConfirmations.toString(), it.requiredConfirmation.toString())
            setViewStates(requiredConfirmationsAvailable)
        }
        var childIndex = 0
        // Add current device first
        if (info.status.isOwner) {
            addSignerView(childIndex++, info.status.sender, false, getString(R.string.this_device))
        }
        // Add confirmed devices
        info.status.owners.forEach {
            if (it != info.status.sender && info.signatures.containsKey(it)) {
                addSignerView(childIndex++, it, false)
            }
        }
        // Add pending devices if more confirmations are required
        if (!requiredConfirmationsAvailable) {
            info.status.owners.forEach {
                if (it != info.status.sender && !info.signatures.containsKey(it)) {
                    addSignerView(childIndex++, it, true)
                }
            }
        }
        (childIndex until layout_submit_transaction_confirmations_addresses.childCount).forEach {
            layout_submit_transaction_confirmations_addresses.removeViewAt(it)
        }
    }

    private fun addSignerView(index: Int, address: BigInteger, pending: Boolean, name: String? = null) {
        val child = layout_submit_transaction_confirmations_addresses.getChildAt(index)
        val vh = child?.tag as? SignatureAddressInfoViewHolder
        if (vh?.currentAddress == address) {
            vh.bind(name, address, pending)
        } else {
            child?.let { layout_submit_transaction_confirmations_addresses.removeView(it) }
            SignatureAddressInfoViewHolder(this, viewProvider).apply {
                bind(name, address, pending)
                layout_submit_transaction_confirmations_addresses.addView(view)
            }
        }
    }

    private fun setViewStates(canSubmit: Boolean, canSign: Boolean = !canSubmit) {
        // If we can submit show information for submitting
        layout_submit_transaction_submit_button.isEnabled = canSubmit
        layout_submit_transaction_all_confirmed_hint.visible(canSubmit)
        // If we can sign show information for signing
        layout_submit_transaction_scan_signature_qr_button.visible(canSign)
        layout_submit_transaction_send_signature_push_button.visible(canSign)
    }

    private fun setUnknownTransactionInfo() {
        layout_submit_transaction_confirmations_hint_text.setFormattedText(R.string.confirm_transaction_hint, "required_confirmations" to "-")
        layout_submit_transaction_confirmations.text = getString(R.string.x_of_x_confirmations, "-", "-")
        setViewStates(false, false)
    }

    override fun inject() {
        DaggerViewComponent.builder()
            .applicationComponent(HeimdallApplication[this].component)
            .viewModule(ViewModule(this))
            .build()
            .inject(this)
    }

    private class SignatureAddressInfoViewHolder(lifecycleOwner: LifecycleOwner, viewProvider: Provider<View>) :
        AddressInfoViewHolder(lifecycleOwner, viewProvider) {

        private var currentName: String? = null

        fun bind(name: String?, address: BigInteger, pending: Boolean) {
            currentName = currentName ?: name
            bind(address)
            view.apply {
                layout_address_item_value.visible(name == null)
                layout_address_item_name.text = currentName
                layout_address_item_name.visible(currentName != null)
                layout_transaction_confirmation_item_status.setImageResource(if (pending) R.drawable.ic_pending_signature else R.drawable.ic_approved_signature)
            }
        }

        override fun onAddressInfo(entry: AddressBookEntry) {
            super.onAddressInfo(entry)
            currentName = entry.name
        }

        override fun start() {
            if (currentName == null) {
                // If we don't have a preset name load
                super.start()
            }
        }
    }

    private data class CachedTransactionData(val safeAddress: BigInteger, val transaction: Transaction)

    companion object {
        private const val REQUEST_CODE_CONFIRM_CREDENTIALS = 2342

        fun createIntent(context: Context, safeAddress: BigInteger?, transaction: Transaction) =
            Intent(context, SubmitTransactionActivity::class.java).apply {
                putExtras(createBundle(safeAddress, transaction))
            }
    }
}
