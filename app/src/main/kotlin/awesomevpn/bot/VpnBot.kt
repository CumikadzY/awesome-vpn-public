package awesomevpn.bot

import awesomevpn.db.admin.Admin
import awesomevpn.db.admin.AdminService
import awesomevpn.db.user.CryptoCurrency
import awesomevpn.db.user.User
import awesomevpn.db.user.UserService
import awesomevpn.domain.netmaker.NetmakerAPI
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.BotApiMethod
import org.telegram.telegrambots.meta.api.objects.Update
import startSuspended

@Component
class VpnBot(
    @Value("\${telegram.token}")
    val token: String,
    val userService: UserService,
    val adminService: AdminService,
    val netmakerAPI: NetmakerAPI
) : TelegramLongPollingBot(), Bot {

    var stopBot = false

    override fun getBotUsername(): String {
        return "Awesome VPN Bot"
    }

    override fun getBotToken() = token

    /*
     *
     * Запуск бота с функции ниже
     *
     */
    @Synchronized
    override fun onUpdateReceived(update: Update) {
        try {
            startSuspended {
                processUpdate(update)
            }
        } catch (e: Exception) {
            println("Пропуск хода.")
        }
    }

    // Создание криптокошельков нового пользователя
    private fun newUserTokens(user: User) {

        //user.cryptoWallets[CryptoCurrency.BTC] = "Находится в разработке."
        user.cryptoWallets[CryptoCurrency.ETH] = "Находится в разработке."
        user.cryptoWallets[CryptoCurrency.USDT] = "Находится в разработке."
        user.cryptoWallets[CryptoCurrency.TRON] = "Находится в разработке."
        user.cryptoWallets[CryptoCurrency.MONERO] = "Находится в разработке."
        userService.saveUser(user)
    }

    // Распределение юзеров/админов
    private suspend fun processUpdate(update: Update) {

        //if(!(update.hasMessage() || update.hasCallbackQuery())) return // Обрабатываем только сообщения и кнопки
        val userName =
            if (update.hasCallbackQuery()) update.callbackQuery.from.userName else update.message.from.userName
        val userId = if (update.hasCallbackQuery()) update.callbackQuery.from.id else update.message.from.id
        val userChatId =
            if (update.hasCallbackQuery()) update.callbackQuery.message.chatId else update.message.chatId.toLong()

        var user = userService.getUserByTgId(userId)
        updateNewAdmin(user)
        val admin = adminService.getAdminById(userId)

        if (admin != null && !admin.asUser) {
            startSuspended { processAdminUpdate(update, admin) }
        } else if (user == null) {
            user = userService.createUser(name = userName, chatId = userChatId, tgId = userId)
            netmakerAPI.createUser(user.tgId)
            startSuspended {
                sendMessage(
                    "Создаем Вам криптокошельки и уникальные файлы конфигурации VPN.\n" +
                            "Это происходит при регистрации нового пользователя и может занять некоторое время.\n",
                    user.chatId
                )
                //newUserTokens(user)
                sendMessage(
                    "Создание криптокошельков завершено.\n" +
                            "Добро пожаловать!", user.chatId, markButtons = startUserMark
                )
                processUserUpdate(update, user)
            }
        } else {
            user.name = userName
            userService.saveUser(user)
            if (user.isBan) return
            startSuspended { processUserUpdate(update, user) }
        }
    }

    //Фиксируем id нового админа
    private fun updateNewAdmin(user: User?) {
        if (user?.name == null) return
        val mbAdmin = adminService.getAdminByName(user.name!!)
        if (mbAdmin != null && mbAdmin.tgId == (-1).toLong() && mbAdmin.chatId == (-1).toLong()) {
            // Сообщаем о регистрации нового админа существующим админам
            val admins = adminService.getAllAdmins()
            for (i in 0 until admins.size) {
                if (admins[i].chatId != (-1).toLong()) {
                    startSuspended { sendMessage("Registered new admin: " + mbAdmin.name, admins[i].chatId) }
                }
            }
            // Сохраняем нового админа в базе
            mbAdmin.tgId = user.tgId
            mbAdmin.chatId = user.chatId
            adminService.saveAdmin(mbAdmin)
        }
    }

    /*
     *
     *  Команды
     *  админов
     *
     */
    suspend fun processAdminUpdate(update: Update, admin: Admin) {
        if (update.hasMessage()) {
            val splittedMessage = update.message.text.split(" ")
            when (splittedMessage[0]) {
                "/start" -> {
                    sendSticker("CAACAgIAAxkBAAEEPlFiOmXSUUeV3-o1Na7NngNZ3KeRhwACxwAD-0HCDoWNjoqE3wv6IwQ", admin.chatId)
                    sendMessage(
                        "Рады тебя видеть, ${admin.name}!", admin.chatId, null, listOf(
                            "Список команд" to "commands"
                        )
                    )
                }
                "/startBot" -> {
                    stopBot = false
                    sendMessage("Bot is started", admin.chatId)
                    val allUsers = userService.getAllUsers()
                    for (usr in allUsers) {
                        sendMessage("Бот запущен админом.", usr.chatId)
                    }
                }
                "/stopBot" -> {
                    stopBot = true
                    sendMessage("Bot is stopped", admin.chatId)
                    val allUsers = userService.getAllUsers()
                    for (usr in allUsers) {
                        sendMessage("Бот остановлен админом.", usr.chatId)
                    }
                }
                "/asUser" -> {
                    admin.asUser = true
                    adminService.saveAdmin(admin)
                    sendSticker(
                        "CAACAgIAAxkBAAEEPk1iOmRR9bwTKMY71SaVKnQuUocqVQACEBUAAjFLyEtoCinYPeRu6CME",
                        admin.chatId
                    )
                    return
                }
                "/enable" -> {
                    if (splittedMessage.size == 1) {
                        sendMessage("Usage: notification 'ID_USER'. You can see tgId in /view users", admin.chatId)
                        return
                    }
                    try {
                        userService.enableUser(splittedMessage[1].toLong())
                        sendMessage("OK", admin.chatId)
                    } catch (e: Exception) {
                        sendSticker(
                            "CAACAgIAAxkBAAEEUe5iRDmCbpHvjYn1rjhwoLpvUxIMEQAC-g0AApuxEEkyE5lzjWNcTSME",
                            admin.chatId
                        )
                        sendMessage("NOK\n" + e.message, admin.chatId)
                    }
                }
                "/deleteNet" -> {
                    if (splittedMessage.size == 1) {
                        sendMessage("Usage: deleteNet 'ID_USER'. You can see tgId in /view users", admin.chatId)
                        return
                    }
                    try {
                        netmakerAPI.deleteUser(splittedMessage[1].toLong())
                        sendMessage("OK", admin.chatId)
                    } catch (e: Exception) {
                        sendSticker(
                            "CAACAgIAAxkBAAEEUe5iRDmCbpHvjYn1rjhwoLpvUxIMEQAC-g0AApuxEEkyE5lzjWNcTSME",
                            admin.chatId
                        )
                        sendMessage("NOK\n" + e.message, admin.chatId)
                    }
                }
                "/createNet" -> {
                    if (splittedMessage.size == 1) {
                        sendMessage("Usage: createNet 'ID_USER'. You can see tgId in /view users", admin.chatId)
                        return
                    }
                    try {
                        netmakerAPI.createUser(splittedMessage[1].toLong())
                        sendMessage("OK", admin.chatId)
                    } catch (e: Exception) {
                        sendSticker(
                            "CAACAgIAAxkBAAEEUe5iRDmCbpHvjYn1rjhwoLpvUxIMEQAC-g0AApuxEEkyE5lzjWNcTSME",
                            admin.chatId
                        )
                        sendMessage("NOK\n" + e.message, admin.chatId)
                    }
                }
                "/rebornNet" -> {
                    if (splittedMessage.size == 1) {
                        sendMessage("Usage: rebornNet 'ID_USER'. You can see tgId in /view users", admin.chatId)
                        return
                    }
                    try {
                        netmakerAPI.deleteUser(splittedMessage[1].toLong())
                        netmakerAPI.createUser(splittedMessage[1].toLong())
                        val usr = userService.getUserByTgId(splittedMessage[1].toLong())
                        if (usr != null) {
                            sendMessage(
                                "Ваши параметры конфигурации обновлены.\nНастройте " +
                                        "Ваш VPN ещё раз с новыми файлами конфигурации. Их можно найти в 'Мои подключения'",
                                usr.chatId
                            )
                        }
                        sendMessage("OK", admin.chatId)
                    } catch (e: Exception) {
                        sendSticker(
                            "CAACAgIAAxkBAAEEUe5iRDmCbpHvjYn1rjhwoLpvUxIMEQAC-g0AApuxEEkyE5lzjWNcTSME",
                            admin.chatId
                        )
                        sendMessage("NOK\n" + e.message, admin.chatId)
                    }
                }
                "/disable" -> {
                    if (splittedMessage.size == 1) {
                        sendMessage("Usage: notification 'ID_USER'. You can see tgId in /view users", admin.chatId)
                        return
                    }
                    try {
                        userService.disableUser(splittedMessage[1].toLong())
                        sendMessage("OK", admin.chatId)
                    } catch (e: Exception) {
                        sendSticker(
                            "CAACAgIAAxkBAAEEUe5iRDmCbpHvjYn1rjhwoLpvUxIMEQAC-g0AApuxEEkyE5lzjWNcTSME",
                            admin.chatId
                        )
                        sendMessage("NOK\n" + e.message, admin.chatId)
                    }
                }
                "/notification" -> {
                    if (splittedMessage.size == 1) {
                        sendMessage("Usage: notification 'TEXT_OF_NOTIFICATION'", admin.chatId)
                        return
                    }
                    val text = "Уведомляем:\n" + splittedMessage.drop(1)
                        .joinToString(separator = " ", transform = { it })
                    for (usr in userService.getAllUsers()) {
                        sendMessage(text, usr.chatId, shielded = true)
                    }
                }
                "/view" -> {
                    if (splittedMessage.size != 2) {
                        sendMessage("Usage: view 'admins|users'", admin.chatId)
                        return
                    }
                    if (splittedMessage[1] in listOf("admin", "admins", "adm", "ad")) {
                        val admins = adminService.getAllAdmins()
                        var adminsOut = "Admins:\n"
                        for (i in 0 until admins.size) {
                            adminsOut += "`" + admins[i].name + "` `" + admins[i].tgId + "`\n"
                        }
                        sendMessage(adminsOut, admin.chatId)
                    } else if (splittedMessage[1] in listOf("user", "users", "usr", "us")) {
                        val users = userService.getAllUsers()
                        var usersOut = "Users:\n"
                        for (i in 0 until users.size) {
                            usersOut += "`" + users[i].name + "` `" + users[i].tgId + "`\n"
                        }
                        sendMessage(usersOut, admin.chatId)
                    } else {
                        sendMessage("Usage: view 'admins|users'", admin.chatId)
                    }
                }
                "/add" -> {
                    if (splittedMessage.size != 2) {
                        sendMessage("Usage: add 'NAME_ADMIN'\nTry again", admin.chatId)
                        return
                    }
                    if (adminService.getAdminByName(splittedMessage[1]) == null) {
                        adminService.saveAdmin(Admin(name = splittedMessage[1], -1, -1))
                        sendMessage("Admin `${splittedMessage[1]}` added.", admin.chatId)
                    } else {
                        sendMessage("Admin with name `${splittedMessage[1]}` already added.", admin.chatId)
                    }
                }
                "/ban" -> {
                    if (splittedMessage.size != 2) {
                        sendMessage("Usage: ban 'ID_USER'", admin.chatId)
                        return
                    }
                    val banUser = userService.getUserByTgId(splittedMessage[1].toLong())
                    if (banUser != null) {
                        banUser.isBan = true
                        sendMessage(
                            "User `${banUser.name}` with id `${banUser.tgId}` has been baned.",
                            admin.chatId
                        )
                        userService.saveUser(banUser)
                    } else {
                        sendMessage("User with id `${splittedMessage[1]}` not found.", admin.chatId)
                    }
                }
                "/unban" -> {
                    if (splittedMessage.size != 2) {
                        sendMessage("Usage: unban 'ID_USER'", admin.chatId)
                        return
                    }
                    val unbanUser = userService.getUserByTgId(splittedMessage[1].toLong())
                    if (unbanUser != null) {
                        unbanUser.isBan = false
                        sendMessage(
                            "User `${unbanUser.name}` with id `${unbanUser.tgId}` has been unbaned.",
                            admin.chatId
                        )
                        userService.saveUser(unbanUser)
                    } else {
                        sendMessage("User with id `${splittedMessage[1]}` not found.", admin.chatId)
                    }
                }
                "/remove" -> {
                    if (splittedMessage.size != 2) {
                        sendMessage("Usage: remove 'NAME_ADMIN'\nTry again", admin.chatId)
                        return
                    }
                    if (splittedMessage[1] == "kuratz") {
                        sendMessage("Are you serious?", admin.chatId)
                        return
                    }
                    val remAdmin = adminService.getAdminByName(splittedMessage[1])
                    if (remAdmin != null) {
                        sendMessage("Removed admin: `${remAdmin.name}`", admin.chatId)
                        if (remAdmin.chatId != (-1).toLong())
                            sendMessage("You've been removed from admins.", remAdmin.chatId)
                        adminService.removeAdmin(remAdmin)
                    } else {
                        sendMessage("Admin with name `${splittedMessage[1]}` not found.", admin.chatId)
                    }
                }
                "/fckingSlaves" -> {
                    sendPhoto("enot.jpg", admin.chatId)
                }
                else -> sendMessage("Несуществующая команда или ошибка исполнения.", admin.chatId)
            }
        } else if (update.hasCallbackQuery()) {
            answerCallbackQuery(update.callbackQuery.id)
            when (update.callbackQuery.data) {
                "commands" -> {
                    sendMessage(
                        "/view 'admins|users' - просмотр ников и tgId админов или юзеров'\n" +
                                "/add 'NAME_ADMIN' - добавить админа по имени'\n" +
                                "/remove 'NAME_ADMIN' - удалить админа по имени'\n" +
                                "/startBot - бот обрабатывает запросы'\n" +
                                "/stopBot - бот обрабатывает запросы'\n" +
                                "/deleteNet 'ID_USER' - удалить юзера из VPN-базы\n" +
                                "/createNet 'ID_USER' - добавить юзера в VPN-базу\n" +
                                "/rebornNet 'ID_USER' - обновить юзера в VPN-базе\n" +
                                "/ban 'ID_USER' - забанить юзера по tgId\n" +
                                "/unban 'ID_USER' - разбанить юзера по tgId\n" +
                                "/enable 'ID_USER' - активировать VPN юзера по tgId\n" +
                                "/disable 'ID_USER' - деактивировать VPN юзера по tgId\n" +
                                "/notification 'TEXT_OF_NOTIFICATION' - выслать всем юзерам сообщение с текстом\n" +
                                "/asUser - войти в систему как пользователь\n" +
                                "/asAdminCum - войти в систему как админ, если в системе уже как пользователь\n",
                        admin.chatId, null, listOf(
                            "Назад" to "start"
                        )
                    )
                }
                "start" -> {
                    sendMessage(
                        "Доброго времени суток, сударь ${admin.name}!", admin.chatId, null, listOf(
                            "Список команд" to "commands"
                        )
                    )
                }
                else -> sendMessage("Несуществующая команда или ошибка исполнения.", admin.chatId)
            }
        }
    }

    /*
     * Команды обычных
     * пользователей
     *
     *
     *
     *
     *
     *
     *
     *
     *
     */
    val startUserMark = listOf( // Стартовое меню по-строчно
        listOf("Баланс", "Мои подключения"),
        listOf("Как подключить VPN?"),
        listOf("Мои криптокошельки", "Пополнить баланс")
    )

    suspend fun processUserUpdate(update: Update, user: User) {
        if (stopBot && !(update.hasMessage() && update.message.text == "/asAdminCum")) return
        if (update.hasMessage()) {
            when (update.message.text) {
                "Меню", "/start" -> {
                    sendMessage(
                        "Здравствуйте" + if (user.name != null) ", " + user.name + "." else {
                            "!"
                        } + "\n" +
                                "Ваша подписка: " + if (user.isActive) "Активна" else {
                            "Не активна"
                        },
                        user.chatId,
                        startUserMark, oneTime = true
                    )
                }
                "Мои криптокошельки" -> {
                    sendMessage(
                        "Ваши криптокошельки:\n\n" + user.cryptoWallets.toList()
                            .joinToString("\n") { "${it.first}: `${it.second}`" },
                        user.chatId,
                        listOf(listOf("Меню"))
                    )
                }
                "Пополнить баланс" -> {
                    sendMessage("На данный момент времени оплата через бота не принимается!\n\n" +
                                "Если вы воспользовались инструкцией, но ваш VPN профиль не работает, напишите одному из админов:" ,
                        user.chatId, null, listOf(
                            "Админ" to "https://t.me/snitron",
                            "Админ" to "https://t.me/imodre",
                            "В меню" to "paybalance!start"
                        )
                    )
                }
                "Мои подключения" -> {
                    sendMessage(
                        "Ваши личные архив и QR-код для настройки VPN:\n" +
                                "Для настройки VPN в меню зайдите в 'Как подключить VPN?'", user.chatId,
                        listOf(listOf("Меню"))
                    )
                    sendDocument(netmakerAPI.getUserConf(user.tgId), "", user.chatId)
                    sendPhoto(netmakerAPI.getQrCode(user.tgId), user.chatId)
                }
                "Баланс" -> {
                    sendMessage("Вы нам важны не из-за денег!\n\n" +
                                "Поэтому мы продоставляем возможность" +
                                " использования VPN-сервиса без оплаты лично для вас," +
                                " однако у нас есть производственные расходы, именно поэтому" +
                                "просим Вас по возможности содействовать нам в данном проекте!" +
                                "Если вы решили поддержать нас, пишите @imodre или @snitron. \n\nСпасибо!", user.chatId, listOf(listOf("Меню"))
                    )


                }
                "Как подключить VPN?" -> {
                    val rr = sendMessage(
                        "${1}/${3}\n" +
                                "Скачайте приложение \'WireGuard\'.\n" +
                                "Его можно скачать, перейдя по кнопкам ниже.\n" +
                                "App Store - для iPhone.\n" +
                                "Play Маркет - для Android.\n" +
                                "И для других платформ(Windows, macOS, Linux).",
                        user.chatId, listOf(listOf("Меню")), listOf(
                            "Вперёд" to "tutorial!tutorial!${2}",
                            "В меню" to "tutorial!start",
                            "Play Маркет" to "https://play.google.com/store/apps/details?id=com.wireguard.android",
                            "App Store" to "https://apps.apple.com/us/app/wireguard/id1441195209",
                            "Для других платформ" to "https://www.wireguard.com/install/"
                        ), true
                    )
                    user.lastMessageId = rr!!.toLong()
                    userService.saveUser(user)
                }
                "/asAdminCum" -> {
                    val checkAdmin = adminService.getAdminById(user.tgId)
                    if (checkAdmin != null) {
                        checkAdmin.asUser = false
                        adminService.saveAdmin(checkAdmin)
                        sendSticker(
                            "CAACAgIAAxkBAAEEPk9iOmRWF-V3YtvLNPdD7pi7bqV47AACWRIAAiyS0Ev9JSCrjYWUiCME",
                            user.chatId
                        )
                    }
                    return
                }
                else -> sendMessage("Несуществующая команда или ошибка исполнения.", user.chatId)
            }
        } else if (update.hasCallbackQuery()) {
            answerCallbackQuery(update.callbackQuery.id)
            val callbackData = update.callbackQuery.data
            val splittedCallBack = callbackData.split("!")
            when (splittedCallBack[1]) {
                "tutorial" -> {
                    val page = splittedCallBack[2].toInt()
                    val endTutorial = 3
                    when (page) {
                        endTutorial -> {
                            editMessage(
                                "${page}/${endTutorial}\n" +
                                        "Для активации Вашего VPN" +
                                        " нажмите на флажок(активировать/подключить туннель) в приложении и сверните приложение " +
                                        "(главное не закрывать его полностью).\n\n" +
                                        "Вот и всё! Ваш личный VPN настроен, " +
                                        "Вы можете пользоваться любыми приложениями и не волноваться о Вашей безопасности.",
                                user.lastMessageId, user.chatId, listOf(
                                    "Назад" to "tutorial!tutorial!${page - 1}",
                                    "В меню" to "tutorial!start"
                                ), true
                            )
                        }
                        1 -> {
                            editMessage(
                                "${page}/${endTutorial}\n" +
                                        "Скачайте приложение \'WireGuard\'.\n" +
                                        "Его можно скачать, перейдя по кнопкам ниже.\n" +
                                        "App Store - для iPhone.\n" +
                                        "Play Маркет - для Android.\n" +
                                        "И для других платформ(Windows, macOS, Linux).",
                                user.lastMessageId, user.chatId, listOf(
                                    "Вперед" to "tutorial!tutorial!${page + 1}",
                                    "В меню" to "tutorial!start",
                                    "Play Маркет" to "https://play.google.com/store/apps/details?id=com.wireguard.android",
                                    "App Store" to "https://apps.apple.com/us/app/wireguard/id1441195209",
                                    "Для других платформ" to "https://www.wireguard.com/install/"
                                ), true
                            )
                        }
                        2 -> {
                            editMessage(
                                "${page}/${endTutorial}\n" +
                                        "При регистрации вы получили уникальные .conf файл и QR-код,\n" +
                                        "они необходимы Вам для настройки VPN.\n" +
                                        "Их можно получить в меню пройдя по кнопке 'Мои подключения'\n" +
                                        "Настройка происходит единожды.\n\n" +
                                        //"Следуйте шагам, показанным на фотографиях ниже:\n" +
                                        "1. Войдите в приложение WireGuard.\n" +
                                        "2. Добавьте туннель. Вам необходимо именно 'импортировать туннель', а не создать пустой новый. \n" +
                                        "3. Выберите удобный Вам способ настройки\n" +
                                        "   ('С помощью файла или архива' - используйте .conf файл, выданный Вам ранее)\n" +
                                        "   ('C помощью QR-кода' - наведите камеру на QR-код, выданный Вам ранее).\n" +
                                        "4. Назовите VPN как Вам угодно.\n" +
                                        "5. Если в приложении появилась строка с выбором VPN, значит настройка прошла успешно!\n" +
                                        "Переходите на следующий шаг настройки.",
                                user.lastMessageId, user.chatId, listOf(
                                    "Назад" to "tutorial!tutorial!${page - 1}",
                                    "Вперед" to "tutorial!tutorial!${page + 1}",
                                    "В меню" to "tutorial!start"
                                ), true
                            )
                        }
                        else -> {
                            // АХТУНГ
                        }
                    }
                }
                "start" -> {
                    sendMessage(
                        "Здравствуйте" + if (user.name != null) ", " + user.name + "." else {
                            "!"
                        } + "\n" +
                                "Ваша подписка: " + if (user.isActive) "Активна" else {
                            "Не активна"
                        },
                        user.chatId,
                        startUserMark, oneTime = true
                    )
                }
            }
        }
    }

    override fun <T : java.io.Serializable, Method : BotApiMethod<T>> execute(method: Method): T = super.execute(method)
}