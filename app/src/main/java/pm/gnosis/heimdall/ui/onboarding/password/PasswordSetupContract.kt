package pm.gnosis.heimdall.ui.onboarding.password

import android.arch.lifecycle.ViewModel
import io.reactivex.Single
import pm.gnosis.svalinn.common.utils.Result

abstract class PasswordSetupContract : ViewModel() {
    abstract fun isPasswordValid(password: String): PasswordValidation
    abstract fun setPassword(password: String, repeat: String): Single<Result<Unit>>
}

class PasswordInvalidException(val reason: PasswordValidation) : Exception()
sealed class PasswordValidation
class PasswordValid(val password: String) : PasswordValidation()
class PasswordNotLongEnough(val numberOfCharacters: Int, val required: Int) : PasswordValidation()
class PasswordsNotEqual : PasswordValidation()
