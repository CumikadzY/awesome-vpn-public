package awesomevpn.db.user

import awesomevpn.db.cryptoinvoice.CryptoInvoice
import awesomevpn.domain.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import startSuspended
import unwrap
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct

@Service
class UserService(
    private val userRepository: UserRepository,
    private val constants: Constants
) : CryptoEventSupplier {

    @PostConstruct
    fun postInit() {
        constants.cryptoEventSupplier = this
    }

    fun saveUser(user: User): User = userRepository.save(user)

    fun removeUser(user: User) = userRepository.delete(user)

    suspend fun createUser(tgId: Long, chatId: Long, name: String?): User {
        val newUser = User(
            tgId = tgId,
            chatId = chatId,
            name = name
        )

        for ((cur, gateway) in constants.cryptoGateways) {
            newUser.cryptoWallets[cur] = gateway.getNewAddress()
        }

        constants.vpnInteractor?.createUser(tgId)

        return withContext(Dispatchers.IO) {
            userRepository.save(newUser)
        }
    }

    fun getAllUsers(): ArrayList<User> {
        return userRepository.findAll() as ArrayList<User>
    }

    fun getUserByTgId(tgId: Long): User? {
        return userRepository.findByTgId(tgId)
    }

    fun getUserById(id: Long): User? {
        return userRepository.findById(id).unwrap()
    }

    fun setRegisteredById(tgId: Long, isRegistered: Boolean) {
        val user = userRepository.findByTgId(tgId)

        user?.isRegistered = isRegistered
        if (user != null) {
            userRepository.save(user)
        }
    }

    override fun getIdsAndAddresses(cryptoCurrency: CryptoCurrency): List<Pair<Long, String>> = userRepository.findAll()
        .map { it.id!! to it.cryptoWallets[cryptoCurrency]!! }

    override fun saveBalance(cryptoInvoice: CryptoInvoice) {
        //TODO: notify balance add

        val user = userRepository.findById(cryptoInvoice.userId).unwrap()
            ?: throw CryptoEventException("No user found for $cryptoInvoice")

        userRepository.changeBalance(user.id!!, cryptoInvoice.calculateInRub())

        startSuspended { checkSubscriptions(withContext(Dispatchers.IO) {
            userRepository.findById(cryptoInvoice.userId)
        }.unwrap()!!) }
    }

    suspend fun enableUser(tgId: Long) = enableUser(getUserByTgId(tgId)!!)

    suspend fun enableUser(user: User) {
        user.subscriptionStart = LocalDateTime.now()
        user.isActive = true
        saveUser(user)
        constants.vpnInteractor?.enableUser(user.tgId)

    }

    suspend fun disableUser(tgId: Long) = disableUser(getUserByTgId(tgId)!!)

    suspend fun disableUser(user: User) {
        user.isActive = false
        saveUser(user)

        constants.vpnInteractor?.disableUser(user.tgId)
    }

    suspend fun checkSubscriptions(user: User) {
        val date = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
         try {
                if (user.isActive) {
                    if (date - user.subscriptionStart.toEpochSecond(ZoneOffset.UTC)
                        > TimeUnit.SECONDS.convert(30, TimeUnit.DAYS)) {
                        if (user.balance >= constants.cost) {
                            user.balance -= user.balance - constants.cost
                            disableUser(user)

                            constants.messageSender?.sendMessage(
                                "\uD83E\uDE99 Бот успешно продлён, с баланса списано ${constants.cost} руб. Текущий баланс: ${getUserById(user.id!!)?.balance}",
                                user.chatId
                            )
                        } else {
                            user.isActive = false

                            constants.messageSender
                                ?.sendMessage("\uD83D\uDCB8 Недостаточно средств для оплаты бота, пополните баланс с помощью команды /pay", user.chatId)

                            saveUser(user)
                        }

                    } else if (date - user.subscriptionStart.toEpochSecond(ZoneOffset.UTC)
                        > TimeUnit.SECONDS.convert(29, TimeUnit.DAYS) ||
                        date - user.subscriptionStart.toEpochSecond(ZoneOffset.UTC)
                        > TimeUnit.SECONDS.convert(29, TimeUnit.DAYS) + TimeUnit.SECONDS.convert(12, TimeUnit.HOURS)) {
                        if (user.balance < constants.cost) {
                            constants.messageSender?.sendMessage(
                                "⌚ В ближайшие дни истекает подписка на VPN, текущего баланса ${getUserById(user.id!!)?.balance} недостаточно для оплаты подписки (${constants.cost}) руб. Пополните баланс с помощью команды /pay",
                                user.chatId
                            )
                        }
                    }
                } else {
                    user.isActive = (date - user.subscriptionStart.toEpochSecond(ZoneOffset.UTC)
                            < TimeUnit.SECONDS.convert(30, TimeUnit.DAYS))

                    saveUser(user)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
    }
}