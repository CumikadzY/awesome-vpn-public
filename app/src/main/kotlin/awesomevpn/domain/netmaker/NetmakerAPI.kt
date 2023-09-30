package awesomevpn.domain.netmaker

import OkHttpUtils
import QRGenerator
import awesomevpn.db.user.UserService
import awesomevpn.domain.Constants
import kassert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.annotation.PostConstruct
import javax.imageio.ImageIO
import kotlin.io.path.Path

interface VPNInteractor {
    suspend fun enableUser(userId: Long): String
    suspend fun disableUser(userId: Long): String
    suspend fun createUser(userId: Long)
    suspend fun deleteUser(userId: Long)
}

class NetmakerAPIException(s: String) : Exception(s)

@Component
class NetmakerAPI(
    @Value("\${netmaker.token}")
    val token: String,
    @Value("\${netmaker.api-url}")
    val apiUrl: String,
    @Value("\${netmaker.gateway-name}")
    val gatewayName: String,
    @Value("\${netmaker.network}")
    val network: String,
    @Value("\${netmaker.api-create-user-url}")
    val apiCreateUserUrl: String,
    @Value("\${static.working-directory}")
    val workingDirectory: String,
    val userService: UserService,
    val constants: Constants
): VPNInteractor {
    private val httpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(TokenInterceptor())
        .connectTimeout(Duration.ofMinutes(1))
        .build()
    private val requestsQueue = Collections.synchronizedList(LinkedList<suspend () -> Unit>())
    private var isCreatingUsers = AtomicBoolean(false)

    private inner class TokenInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val newReq = chain.request()
                .newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
            return chain.proceed(newReq)
        }
    }

    @PostConstruct
    private fun ensureFolders() {
        constants.vpnInteractor = this

        try {
            Files.createDirectory(Path("$workingDirectory/configs"))
        } catch (_: FileAlreadyExistsException) {
        }

        try {
            Files.createDirectory(Path("$workingDirectory/qrs"))
        } catch (_: FileAlreadyExistsException) {
        }
    }

    private suspend fun getUserNamesList(): List<String> {
        val request = Request.Builder()
            .url(apiUrl)
            .get()
            .build()

        var response = OkHttpUtils.makeAsyncRequest(
            client = httpClient,
            request = request
        ) ?: throw NetmakerAPIException("Bad code")

        if (response.trim() == "null") {
            response = "[]"
        }

        val jsonArray = JSONArray(response)
        val userNames = arrayListOf<String>()

        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)

            userNames.add(jsonObject.getString("clientid"))
        }

        return userNames
    }

    override suspend fun deleteUser(userId: Long) {
        val createRequest = Request.Builder()
            .url("${apiUrl}/${network}/${userId}")
            .delete("".toRequestBody())
            .build()

        OkHttpUtils.makeAsyncRequest(httpClient, createRequest) ?: throw NetmakerAPIException("Bad code")
        userService.setRegisteredById(userId, false)

        if (File("configs/$userId.conf").exists()) {
            withContext(Dispatchers.IO) {
                File("configs/$userId.conf").delete()
            }
        }

        if (File("qrs/$userId.png").exists()) {
            withContext(Dispatchers.IO) {
                File("qrs/$userId.png").delete()
            }
        }
    }

    override suspend fun enableUser(userId: Long) = changeUserValue(prevName = userId.toString(), enabled = true)

    override suspend fun disableUser(userId: Long) = changeUserValue(prevName = userId.toString(), enabled = false)

    private suspend fun changeUserValue(
        prevName: String,
        newName: String = prevName,
        enabled: Boolean = true
    ): String {
        val updateNameRequest = Request.Builder()
            .url("${apiUrl}/${network}/${prevName}")
            .put("{\"clientid\": \"$newName\", \"enabled\": $enabled}".toRequestBody(OkHttpUtils.JSON_TYPE))
            .build()

        return OkHttpUtils.makeAsyncRequest(httpClient, updateNameRequest) ?: throw NetmakerAPIException("Bad code")
    }

    private suspend fun createUserByAPI() {
        val createRequest = Request.Builder()
            .url(apiCreateUserUrl)
            .post("{\"gatewayId\": \"${gatewayName}\"}".toRequestBody())
            .build()

        OkHttpUtils.makeAsyncRequest(httpClient, createRequest) ?: throw NetmakerAPIException("Bad code")
    }

    override suspend fun createUser(userId: Long) {
        kassert(!(userService.getUserByTgId(userId)?.isRegistered ?: false), NetmakerAPIException("Already registered"))

        synchronized(requestsQueue) {
            requestsQueue.add {
                val usersBefore = getUserNamesList().toSet()
                createUserByAPI()
                val usersAfter = getUserNamesList().toSet()
                val userName = (usersAfter - usersBefore).first()

                changeUserValue(userName, userId.toString())
            }
        }

        if (!isCreatingUsers.get()) {
            GlobalScope.launch {
                isCreatingUsers.set(true)

                while (requestsQueue.isNotEmpty()) {
                    try {
                        synchronized(requestsQueue) {
                            requestsQueue.removeFirst()
                        }.invoke()

                        userService.setRegisteredById(userId, true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                isCreatingUsers.set(false)
            }
        }
    }

    suspend fun getUserConf(tgId: Long): File {
        val file = File("$workingDirectory/configs/${tgId}.conf")

        if (!file.exists()) {
            withContext(Dispatchers.IO) { file.createNewFile() }
            val request = Request.Builder()
                .url("${apiUrl}/${network}/${tgId}/file")
                .get()
                .build()
            val call = OkHttpUtils.makeAsyncRequestRaw(httpClient, request)
            val inputStream = call.body?.byteStream() ?: kotlin.run {
                call.body?.close()
                call.close()

                throw NetmakerAPIException("Bad source")
            }

            val buffer = ByteArray(1024)
            val outputStream = withContext(Dispatchers.IO) {
                FileOutputStream(file)
            }

            while (true) {
                val read = withContext(Dispatchers.IO) {
                    inputStream.read(buffer)
                }

                if (read == -1) {
                    break
                }

                withContext(Dispatchers.IO) {
                    outputStream.write(buffer, 0, read)
                }
            }

            withContext(Dispatchers.IO) {
                outputStream.flush()
                outputStream.close()
                inputStream.close()
            }

            call.body?.close()
            call.close()
        }

        return file
    }

    suspend fun getQrCode(tgId: Long): File {
        val file = File("$workingDirectory/qrs/${tgId}.png")

        if (!file.exists()) {
            val config = getUserConf(tgId)

            withContext(Dispatchers.IO) {
                ImageIO.write(QRGenerator.getCodeForConfig(config.readText()), "png", file)
            }
        }

        return file
    }
}