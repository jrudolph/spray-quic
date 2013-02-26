package net.virtualvoid.quic

import akka.actor._
import akka.actor.DeadLetter

object Main extends App {
  val system = ActorSystem()

  system.actorOf(Props[QuicServer])
  system.actorOf(Props(new Actor {
    system.eventStream.subscribe(self, classOf[DeadLetter])
    system.eventStream.subscribe(self, classOf[UnhandledMessage])
    def receive: Receive = {
      case d: DeadLetter => println("Deadletter: "+d)
      case d: UnhandledMessage => println("Unhandled: "+d)
    }
  }))
}
