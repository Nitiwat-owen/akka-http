package part3_highlevelserver

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import part2_lowlevelserver.{Guitar, GuitarDB, GuitarStoreJsonProtocol}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

// step 1
import spray.json._

object HighLevelExample extends App with GuitarStoreJsonProtocol{
  implicit val system = ActorSystem("HighLevelExample")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher
  import GuitarDB._
  /**
   * GET /api/guitar fetches ALL the guitars in the store
   * GET /api/guitar?id=x fetches the guitar with id X
   * GET /api/guitar/X fetches guitar with id X
   * GET /api/guitar/inventory?inStock=true
   */

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

  implicit val timeout = Timeout(2 seconds)
  val guitarServerRoute =
    path("api" / "guitar") {
      parameter('id.as[Int]) { guitarId: Int =>
        get {
          val guitarFuture: Future[Option[Guitar]] = (guitarDb ? FindGuitar(guitarId)).mapTo[Option[Guitar]]
          val entityFuture = guitarFuture.map({ guitarOption: Option[Guitar] => {
            HttpEntity(
              ContentTypes.`application/json`,
              guitarOption.toJson.prettyPrint
            )
          }
          })
          complete(entityFuture)
        }
      } ~
      get {
        val guitarsFuture: Future[List[Guitar]] = (guitarDb ? FindAllGuitars).mapTo[List[Guitar]]
        val entityFuture = guitarsFuture.map({ guitars: List[Guitar] =>
          HttpEntity(
            ContentTypes.`application/json`,
            guitars.toJson.prettyPrint
          )
        })
        complete(entityFuture)
      }
    } ~
    path("api" / "guitar" / IntNumber) {guitarId => {
      get {
        val guitarFuture: Future[Option[Guitar]] = (guitarDb ? FindGuitar(guitarId)).mapTo[Option[Guitar]]
        val entityFuture = guitarFuture.map({ guitarOption: Option[Guitar] => {
          HttpEntity(
            ContentTypes.`application/json`,
            guitarOption.toJson.prettyPrint
          )
        }})
        complete(entityFuture)
      }
    }} ~
    path ("api" / "guitar" / "inventory") {
      get {
        parameter('inStock.as[Boolean]) { inStock : Boolean => {
          val guitarsFuture: Future[List[Guitar]] = (guitarDb ? FindGuitarsInStock(inStock)).mapTo[List[Guitar]]
          val entityFuture = guitarsFuture.map({ guitars: List[Guitar] =>
            HttpEntity(
              ContentTypes.`application/json`,
              guitars.toJson.prettyPrint
            )
          })
          complete(entityFuture)
        }}
      }
    }

  def toHttpEntity(payload: String) = HttpEntity(ContentTypes.`application/json`, payload)

  val simplifiedGuitarServerRoute =
    (pathPrefix("api" / "guitar")  & get){
      path("inventory") {
        parameter('inStock.as[Boolean]) { inStock: Boolean => {
          complete(
            (guitarDb ? FindGuitarsInStock(inStock))
              .mapTo[List[Guitar]]
              .map(_.toJson.prettyPrint)
              .map(toHttpEntity)
          )
        }}
      } ~
      (path(IntNumber) | parameter('id.as[Int])) { guitarId : Int =>
        complete(
          (guitarDb ? FindGuitar(guitarId))
            .mapTo[Option[Guitar]]
            .map(_.toJson.prettyPrint)
            .map(toHttpEntity)
        )
      } ~
      pathEndOrSingleSlash {
        complete(
          (guitarDb ? FindAllGuitars)
            .mapTo[List[Guitar]]
            .map(_.toJson.prettyPrint)
            .map(toHttpEntity)
        )
      }
    }
  Http().bindAndHandle(simplifiedGuitarServerRoute, "localhost", 8080)
}
