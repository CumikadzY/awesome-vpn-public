package awesomevpn.domain.crypto

import awesomevpn.domain.api.BlockCypherAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.SendRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.prefs.Preferences
import kotlin.math.pow

@Component
class BitcoinAPI(
    @Value("\${crypto.bitcoin.seed-phrase}")
    private val seedPhrase: String,
    @Value("\${debug}")
    private val blockCypherAPI: BlockCypherAPI,
    private val usersAddresses: List<String>
) : CryptoGateway {
    private val walletAppKit: WalletAppKit
    private val params: NetworkParameters = MainNetParams.get()
    private val pendingTxs = hashSetOf<String>()

    init {
        val dirBitcoinj = File("static/bitcoinj")
        if (!dirBitcoinj.exists()) {
            dirBitcoinj.mkdir()
        }

        BriefLogFormatter.initWithSilentBitcoinJ()

        walletAppKit = WalletAppKit(params, Script.ScriptType.P2WPKH, null, dirBitcoinj, "vpn")

        walletAppKit.setAutoSave(true)
        walletAppKit.startAsync()
        walletAppKit.awaitRunning()

        val seed = DeterministicSeed(seedPhrase, null, "", 0)
        walletAppKit.restoreWalletFromSeed(seed)

        walletAppKit.wallet().addCoinsReceivedEventListener { _, tx, _, _ ->
            val addresses = tx.outputs.map {
                it.scriptPubKey.getToAddress(params)
            }

            usersAddresses.forEach { address ->
                if (Address.fromString(params, address) in addresses) {
                    pendingTxs.add(tx.txId.toString())
                }
            }
        }

        walletAppKit.wallet().addTransactionConfidenceEventListener { _, tx ->
            val toDeleteFromPending = hashSetOf<String>()

            if (tx.txId.toString() in pendingTxs && tx.confidence.depthInBlocks >= 1) {
                toDeleteFromPending.add(tx.txId.toString())

            }
            pendingTxs.removeAll(toDeleteFromPending)
        }
    }

    override fun getMasterBalance(): Long {
        return walletAppKit.wallet().balance.value
    }

    override fun getNewAddress(): String {
        val newKey = walletAppKit.wallet().freshReceiveKey()
        val nnaddress = Address.fromKey(params, newKey, Script.ScriptType.P2WPKH)

        walletAppKit.wallet().addWatchedAddress(nnaddress)

        return nnaddress.toString()
    }

    // amount == null => max
    override suspend fun sendFundsOnAddress(address: String, amount: Double?, fee: Long?): String {
        val receiver = Address.fromString(params, address)

        val f = fee ?: blockCypherAPI.getSuggestedFee()

        if (amount == null) {
            val sendRequest = SendRequest.emptyWallet(receiver)
            sendRequest.feePerKb = Coin.valueOf(f)

            val requestResult = walletAppKit.wallet().sendCoins(sendRequest)

            Preferences.userRoot().putDouble(
                "fee",
                Preferences.userRoot().getDouble("fee", 0.0) - requestResult.tx.fee.value / 10.0.pow(8)
            )

            return withContext(Dispatchers.IO) {
                requestResult.broadcastComplete.get()
            }?.txId.toString()
        } else {
            val sendRequest = SendRequest.to(receiver, Coin.valueOf((amount * 10.0.pow(8)).toLong()))
            sendRequest.feePerKb = Coin.valueOf(f)

            val sendRequestResult = walletAppKit.wallet().sendCoins(sendRequest)

            Preferences.userRoot().putDouble(
                "fee",
                Preferences.userRoot().getDouble("fee", 0.0) - sendRequestResult.tx.fee.value / 10.0.pow(8)
            )

            return withContext(Dispatchers.IO) {
                sendRequestResult.broadcastComplete.get()
            }?.txId.toString()
        }
    }

}