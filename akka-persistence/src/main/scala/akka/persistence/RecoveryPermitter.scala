/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence

import java.util.ArrayDeque
import akka.annotation.InternalApi
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated

/**
 * INTERNAL API
 */
@InternalApi private[akka] object RecoveryPermitter {
  def props(maxPermits: Int): Props =
    Props(new RecoveryPermitter(maxPermits))

  case object RequestRecoveryPermit
  case object RecoveryPermitGranted
  case object ReturnRecoveryPermit

}

/**
 * INTERNAL API: When starting many persistent actors at the same time the journal
 * its data store is protected from being overloaded by limiting number
 * of recoveries that can be in progress at the same time.
 */
@InternalApi private[akka] class RecoveryPermitter(maxPermits: Int) extends Actor with ActorLogging {
  import RecoveryPermitter._

  private var permits = 0
  private val pending = new ArrayDeque[ActorRef]
  private var maxPendingStats = 0

  def receive = {
    case RequestRecoveryPermit ⇒
      context.watch(sender())
      if (permits >= maxPermits) {
        if (pending.isEmpty)
          log.debug("Exceeded max-concurrent-recoveries [{}]", maxPermits)
        pending.offer(sender())
        maxPendingStats = math.max(maxPendingStats, pending.size)
      } else {
        recoveryPermitGranted(sender())
      }

    case ReturnRecoveryPermit ⇒
      returnRecoveryPermit(sender())

    case Terminated(ref) ⇒
      // pre-mature termination should be rare
      if (!pending.remove(ref))
        returnRecoveryPermit(ref)
  }

  private def returnRecoveryPermit(ref: ActorRef): Unit = {
    permits -= 1
    context.unwatch(ref)
    if (permits < 0) throw new IllegalStateException("permits must not be negative")
    if (!pending.isEmpty) {
      val ref = pending.poll()
      recoveryPermitGranted(ref)
    }
    if (pending.isEmpty && maxPendingStats > 0) {
      log.debug(
        "Drained pending recovery permit requests, max in progress was [{}], still [{}] in progress",
        permits + maxPendingStats, permits)
      maxPendingStats = 0
    }
  }

  private def recoveryPermitGranted(ref: ActorRef): Unit = {
    permits += 1
    ref ! RecoveryPermitGranted
  }

}
