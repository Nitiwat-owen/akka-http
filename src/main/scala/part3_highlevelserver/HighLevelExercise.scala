package part3_highlevelserver

import akka.actor.{ActorLogging, ActorSystem}
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import spray.json._

import scala.util.Success
import scala.util.Failure
import scala.concurrent.duration._

case class Person(pin: Int, name: String)

trait PersonToJsonProtocol extends DefaultJsonProtocol {
  implicit val personFormat = jsonFormat2(Person)
}

object HighLevelExercise extends App with PersonToJsonProtocol {
  implicit val system = ActorSystem("HighLevelExercise")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  /**
   * Exercise
   * - GET /api/people: retrieve ALL the people you have registered
   * - GET /api/people/pin : retrieve the person with that PIN, return as JSON
   * - GET /api/people?pin=X (same as above)
   * - POST /api/people with a JSON payload denoting a Person, add that person to your database
   */

  var people = List(
    Person(1, "Alice"),
    Person(2, "Bob"),
    Person(3, "Charlie")
  )

  def toHttpEntity(payload: String) = HttpEntity(ContentTypes.`application/json`, payload)

  val personServerRoute =
    pathPrefix("api"/ "people") {
      get {
        (path(IntNumber) | parameter('pin.as[Int])) { pin: Int => {
          complete(
            HttpEntity(
              ContentTypes.`application/json`,
              people.find(_.pin == pin).toJson.prettyPrint
            )
          )
        }} ~
        pathEndOrSingleSlash {
          complete(
            HttpEntity(
              ContentTypes.`application/json`,
              people.toJson.prettyPrint
            )
          )
        }
      } ~
      (post & pathEndOrSingleSlash & extractRequest & extractLog) { (request : HttpRequest, log : LoggingAdapter)  =>
        val entity = request.entity
        val strictEntityFuture = entity.toStrict(2 seconds)
        val personFuture = strictEntityFuture.map({ strictEntity =>
          strictEntity.data.utf8String.parseJson.convertTo[Person]
        })
        personFuture.onComplete {
          case Success(person) =>
            log.info(s"Got person: $person")
            people = people :+ person
          case Failure(ex) =>
            log.warning(s"Something failed wiht fetching the person from the entity: $ex")
        }

        complete(personFuture
          .map(_ => StatusCodes.OK)
          .recover {
            case _ => StatusCodes.InternalServerError
        })
      }
    }

  Http().bindAndHandle(personServerRoute, "localhost", 8080)
}
