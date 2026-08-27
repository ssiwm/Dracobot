package eu.draconest.hermesbots.data

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Development-only support for a local gateway with a self-signed certificate.
 * This class is compiled only into debug builds and is never packaged in release APKs.
 */
object DebugGatewayTls {
    @JvmStatic
    fun apply(base: OkHttpClient): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        return base.newBuilder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
