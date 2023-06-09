package part2_lowlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.stream.ActorMaterializer

import java.io.InputStream
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

object HttpsContext {
  // Step 1: key store
  val ks: KeyStore = KeyStore.getInstance("PKCS12")
  val keystoreFile: InputStream = getClass.getClassLoader.getResourceAsStream("keystore.pkcs12")
  // alternative new FileInputStream(new File("src/main/resources/keystore.pkcs12"))
  val password = "akka-https".toCharArray // never store password on code explicitly
  ks.load(keystoreFile, password)

  // Step 2: initialize a key manager
  val keyManagerFactory = KeyManagerFactory.getInstance("SunX509") // PKI = public key infrastructure
  keyManagerFactory.init(ks, password)

  // Step 3: initialize a trust manager
  val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
  trustManagerFactory.init(ks)

  // Step 4: initialize an SSL context
  val sslContext: SSLContext = SSLContext.getInstance("TLS")
  sslContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom())

  // Step 5: return the https connection context
  val httpsConnectionContext: HttpsConnectionContext = ConnectionContext.https(sslContext)
}

object LowLevelHttps extends App {
  implicit val system = ActorSystem("LowLevelHttps")
  implicit val materializer = ActorMaterializer()

  val requestHandler: HttpRequest => HttpResponse = {
    case HttpRequest(HttpMethods.GET, uri, headers, entity, protocol) =>
      HttpResponse(StatusCodes.OK,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   Hello from Akka
            | </body>
            |</html>
            |""".stripMargin
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(
        StatusCodes.NotFound,
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   OOPS! The resource can't be found.
            | </body>
            |</html>
            |""".stripMargin
        )
      )
  }

  val httpsBinding = Http().bindAndHandleSync(requestHandler, "localhost", 8443, HttpsContext.httpsConnectionContext)
}
