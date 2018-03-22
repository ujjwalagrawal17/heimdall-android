package pm.gnosis.heimdall.ui.onboarding.account

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import com.google.android.gms.auth.api.credentials.Credential
import com.jakewharton.rxbinding2.view.clicks
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.android.synthetic.main.layout_account_setup.*
import pm.gnosis.heimdall.HeimdallApplication
import pm.gnosis.heimdall.R
import pm.gnosis.heimdall.common.di.components.DaggerViewComponent
import pm.gnosis.heimdall.common.di.modules.ViewModule
import pm.gnosis.heimdall.data.repositories.impls.ActivityShouldRequestCredentialDialogException
import pm.gnosis.heimdall.data.repositories.impls.CredentialDialogAction
import pm.gnosis.heimdall.data.repositories.impls.NoAccountsAvailableWithSmartLockException
import pm.gnosis.heimdall.reporting.ScreenId
import pm.gnosis.heimdall.ui.base.BaseActivity
import pm.gnosis.heimdall.ui.onboarding.SetupSafeIntroActivity
import pm.gnosis.heimdall.ui.onboarding.account.create.GenerateMnemonicActivity
import pm.gnosis.svalinn.common.utils.startActivity
import pm.gnosis.svalinn.common.utils.subscribeForResult
import pm.gnosis.svalinn.common.utils.visible
import timber.log.Timber
import javax.inject.Inject

class AccountSetupActivity : BaseActivity() {
    override fun screenId() = ScreenId.ACCOUNT_SETUP

    @Inject
    lateinit var viewModel: AccountSetupContract

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inject()
        setContentView(R.layout.layout_account_setup)
    }

    override fun onStart() {
        super.onStart()
        disposables += layout_account_setup_google.clicks()
            .flatMapSingle {
                viewModel.continueWithGoogle()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSubscribe { isLoading(true) }
                    .doAfterTerminate { isLoading(false) }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeForResult(onNext = { onAccountCreated() }, onError = ::onContinueWithGoogleError)


        disposables += layout_account_setup_advanced.clicks()
            .subscribeBy(onNext = { startActivity(GenerateMnemonicActivity.createIntent(this)) }, onError = Timber::e)
    }

    private fun onAccountCreated() {
        startActivity(SetupSafeIntroActivity.createIntent(this), clearStack = true)
    }

    private fun isLoading(isLoading: Boolean) {
        layout_account_setup_google.isEnabled = !isLoading
        layout_account_setup_progress_bar.visible(isLoading)
    }

    private fun onContinueWithGoogleError(throwable: Throwable) {
        Timber.e(throwable)
        (throwable as? ActivityShouldRequestCredentialDialogException)?.request(this)
        (throwable as? NoAccountsAvailableWithSmartLockException)?.let { showNoAccountSupportDialog() }
    }

    private fun showNoAccountSupportDialog() {
        AlertDialog.Builder(this)
            .setPositiveButton("Ok", { _, _ -> })
            .setTitle("SmartLock not enabled")
            .setMessage(Html.fromHtml("Your Google account doesn't have SmartLock enabled or it is disabled for this app. Please enable it to continue with Google. <a href=\"https://support.google.com/accounts/answer/6197437\">More information here.</a>"))
            .show()
            .apply { findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CredentialDialogAction.READ.requestCode && resultCode == RESULT_OK) {
            data?.getParcelableExtra<Credential>(Credential.EXTRA_KEY)?.let { onCredentials(it) }
        } else if (requestCode == CredentialDialogAction.STORE.requestCode && resultCode == RESULT_OK) {
            disposables += viewModel.continueWithGoogle()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeBy(onSuccess = { onAccountCreated() }, onError = ::onContinueWithGoogleError)
        }
    }

    private fun onCredentials(credential: Credential) {
        // Sign the user in with information from the Credential
        disposables += viewModel.setAccountFromCredential(credential)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(onComplete = { onAccountCreated() }, onError = Timber::e)
    }

    private fun inject() {
        DaggerViewComponent.builder()
            .applicationComponent(HeimdallApplication[this].component)
            .viewModule(ViewModule(this))
            .build().inject(this)
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, AccountSetupActivity::class.java)
    }
}
