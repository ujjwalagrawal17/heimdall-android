package pm.gnosis.heimdall.data.repositories.models

import pm.gnosis.heimdall.data.db.models.ERC20TokenDb
import pm.gnosis.utils.stringWithNoTrailingZeroes
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

data class ERC20Token(
    val address: BigInteger,
    val name: String? = null,
    val symbol: String? = null,
    val decimals: Int,
    val verified: Boolean = false
) {
    fun convertAmount(unscaledAmount: BigInteger): BigDecimal =
        BigDecimal(unscaledAmount).setScale(decimals).div(BigDecimal.TEN.pow(decimals))

    companion object {
        val ETHER_TOKEN = ERC20Token(BigInteger.ZERO, decimals = 18, symbol = "ETH", name = "Ether")
    }

}

data class ERC20TokenWithBalance(val token: ERC20Token, val balance: BigInteger? = null) {
    fun displayString() =
        balance?.let {
            "${token.convertAmount(balance).setScale(5, RoundingMode.DOWN).stringWithNoTrailingZeroes()} ${token.symbol}"
        } ?: "-"
}

fun ERC20Token.toDb() = ERC20TokenDb(address, name, symbol, decimals, verified)
fun ERC20TokenDb.fromDb() = ERC20Token(address, name, symbol, decimals, verified)
