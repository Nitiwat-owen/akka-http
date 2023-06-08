package part2_lowlevelserver

import akka.pattern.ask
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import part2_lowlevelserver.GuitarDB.{AddQuantity, CreateGuitar, FindAllGuitars, FindGuitar, FindGuitarsInStock, GuitarCreated}
import spray.json._

import scala.concurrent.Future
import scala.concurrent.duration._

case class Guitar(make: String, model: String, quantity: Int = 0)

object GuitarDB {
  case class CreateGuitar(guitar: Guitar)
  case class GuitarCreated(id: Int)
  case class FindGuitar(id: Int)
  case object FindAllGuitars
  case class FindGuitarsInStock(inStock : Boolean)
  case class AddQuantity(id: Int, quantity: Int)
}

class GuitarDB extends Actor with ActorLogging {
  import GuitarDB._

  var guitars: Map[Int, Guitar] = Map()
  var currentGuitarId = 0

  override def receive: Receive = {
    case FindAllGuitars =>
      log.info("Searching for all guitars")
      sender() ! guitars.values.toList
    case FindGuitar(id) =>
      log.info(s"Searching guitar by id: $id")
      sender() ! guitars.get(id)
    case CreateGuitar(guitar) =>
      log.info(s"Adding $guitar with id $currentGuitarId")
      guitars = guitars + (currentGuitarId -> guitar)
      sender() ! GuitarCreated(currentGuitarId)
      currentGuitarId += 1
    case AddQuantity(id, quantity) =>
      log.info(s"Trying to add $quantity item to guitar id $id")
      val guitar : Option[Guitar] = guitars.get(id)
      val newGuitar: Option[Guitar] = guitar.map({
        case Guitar(make, model, q) => Guitar(make, model, q + quantity)
      })
      newGuitar.foreach(guitar => guitars = guitars + (id -> guitar))
      sender() ! newGuitar
    case FindGuitarsInStock(inStock) =>
      log.info(s"Searching for all guitars ${if(inStock) "in" else "out of"} stock")
      if (inStock) {
        sender() ! guitars.values.filter(_.quantity > 0)
      } else {
        sender() ! guitars.values.filter(_.quantity == 0)
      }

  }
}

trait GuitarStoreJsonProtocol extends DefaultJsonProtocol {
  implicit val guitarFormat = jsonFormat3(Guitar)
}

object LowLevelRest extends App with GuitarStoreJsonProtocol {
  implicit val system = ActorSystem("LowLevelRest")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  /*
    GET on localhost:8080/api/guitar => ALL the guitars in the store
    GET on localhost:8080/api/guitar?id=X => fetches the guitar associated with id X
    POST on localhost:8080/api/guitar => insert the guitar into the store
   */

  // JSON -> marshalling
  val simpleGuitar = Guitar("Fender", "Stratocaster")
  println(simpleGuitar.toJson.prettyPrint)

  // unmarshalling
  val simpleGuitarJsonString =
    """
      |{
      | "make": "Fender",
      | "model": "Stratcaster",
      | "quantity":3
      |}
      |""".stripMargin
  println(simpleGuitarJsonString.parseJson.convertTo[Guitar])

  /*
    set up
   */
  val guitarDb = system.actorOf(Props[GuitarDB], "LowLevelGuitarDB")

  val guitarList = List(
    Guitar("Fender", "Startocaster"),
    Guitar("Gibson", "Les Paul"),
    Guitar("Martin", "LX1")
  )

  guitarList.foreach({ guitar =>
    guitarDb ! CreateGuitar(guitar)
  })

  /*
    server code
   */
  implicit val defaultTimeout = Timeout(2 seconds)

  def getGuitar(query: Query): Future[HttpResponse] = {
    val guitarId = query.get("id").map(_.toInt) // Option[Int]

    guitarId match {
      case None => Future(HttpResponse(StatusCodes.NotFound))
      case Some(id: Int) =>
        val guitarFuture: Future[Option[Guitar]] = (guitarDb ? FindGuitar(id)).mapTo[Option[Guitar]]
        guitarFuture.map({
          case None => HttpResponse(StatusCodes.NotFound)
          case Some(guitar) => HttpResponse(
            entity = HttpEntity(
              ContentTypes.`application/json`,
              guitar.toJson.prettyPrint
            ))
        })
    }
  }

  val requestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar"), _, _, _) =>
      /*
        query parameter handling code

        localhost:8080/api/endpoint?param1=value1&param2=value2
       */
      val query = uri.query()
      if (query.isEmpty) {
        val guitarFuture: Future[List[Guitar]] = (guitarDb ? FindAllGuitars).mapTo[List[Guitar]]
        guitarFuture.map({ guitars =>
          HttpResponse(
            entity = HttpEntity(
              ContentTypes.`application/json`,
              guitars.toJson.prettyPrint
            )
          )
        })
      } else {
        // fetch guitar associated to the guitar id
        getGuitar(query)
      }

    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      val query = uri.query()
      val inStockOption = query.get("inStock").map(_.toBoolean)

      inStockOption match {
        case Some(inStock : Boolean) =>
          val guitarsFuture: Future[List[Guitar]] = (guitarDb ? FindGuitarsInStock(inStock)).mapTo[List[Guitar]]
          guitarsFuture.map({guitars =>
            HttpResponse(
              entity = HttpEntity(
                ContentTypes.`application/json`,
                guitars.toJson.prettyPrint
              )
            )
          })
        case None => Future(HttpResponse(StatusCodes.BadRequest))
      }

    case HttpRequest(HttpMethods.POST, Uri.Path("/api/guitar"), _, entity, _) =>
      val strictEntityFuture = entity.toStrict(3 seconds)
      strictEntityFuture.flatMap({strictEntity =>
        val guitarJsonString = strictEntity.data.utf8String
        val guitar = guitarJsonString.parseJson.convertTo[Guitar]

        val guitarCreatedFuture : Future[GuitarCreated] = (guitarDb ? CreateGuitar(guitar)).mapTo[GuitarCreated]
        guitarCreatedFuture.map({ guitarCreated =>
          HttpResponse(StatusCodes.OK)
        })
      })

    case HttpRequest(HttpMethods.POST, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      val query = uri.query()
      val guitarId : Option[Int] = query.get("id").map(_.toInt)
      val guitarQuantity: Option[Int] = query.get("quantity").map(_.toInt)

      val validGuitarResponseFuture : Option[Future[HttpResponse]] = for {
        id <- guitarId
        quantity <- guitarQuantity
      } yield {
        val newGuitarFuture : Future[Option[Guitar]] = (guitarDb ? AddQuantity(id, quantity)).mapTo[Option[Guitar]]
        // turn Future Option Guitar -> Future of HTTP Response
        newGuitarFuture.map({_ => HttpResponse(StatusCodes.OK)})
      }

      validGuitarResponseFuture.getOrElse(Future(HttpResponse(StatusCodes.BadRequest)))

    case request: HttpRequest =>
      request.discardEntityBytes()
      Future{
        HttpResponse(status = StatusCodes.NotFound)
      }

  }

  Http().bindAndHandleAsync(requestHandler, "localhost", 8080)

  /**
   * Exercise: enhance the Guitar case class with a quantity field, by default 0
   * - GET to /api/guitar/inventory?inStock=true/false which returns the guitars in stock as a JSON
   * - POST to /api/guitar/inventory?id=X&quantity=y which adds Y guitars to the stock for guitar with id X
   *
   */
}
