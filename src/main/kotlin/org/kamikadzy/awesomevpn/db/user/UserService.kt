package org.kamikadzy.awesomevpn.db.user

import org.kamikadzy.awesomevpn.utils.Constants
import org.kamikadzy.awesomevpn.utils.unwrap
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class UserService(
    val userRepository: UserRepository,
    val constants: Constants
) {
    fun saveUser(user: User) = userRepository.save(user)

    fun removeUser(user: User) = userRepository.delete(user)

    fun createUser(tgId: Long, chatId: Long, name: String?): User {
        val newUser = User(
            tgId = tgId,
            chatId = chatId,
            name = name
        )

        for ((cur, gateway) in constants.cryptoGateways) {
            newUser.cryptoWallets[cur] = gateway.getNewAddress()
        }

        return userRepository.save(newUser)
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

    fun setActiveById(tgId: Long, isActive: Boolean) {
        val user = userRepository.findByTgId(tgId)

        user?.isActive = isActive

        if (user != null) {
            userRepository.save(user)
        }
    }


    fun gotPayment(amount: BigDecimal) {

    }
}