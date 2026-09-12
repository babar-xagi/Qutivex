package dev.qutivex.engine.dependency

import java.net.HttpURLConnection
import java.net.URI

/**
 * Abstraction for communicating with remote Maven repositories.
 */
interface RepositoryClient {
    fun isReachable(repositoryUrl: String = DEFAULT_REPOSITORY): Boolean

    companion object {
        const val DEFAULT_REPOSITORY = "https://repo.maven.apache.org/maven2/"
    }
}

class HttpRepositoryClient : RepositoryClient {
    override fun isReachable(repositoryUrl: String): Boolean {
        return try {
            val uri = URI(repositoryUrl)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.responseCode in 200..399
        } catch (_: Exception) {
            false
        }
    }
}
