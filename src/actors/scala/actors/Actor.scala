/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2006, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

package scala.actors

import scala.collection.mutable.{HashSet, Stack}
import compat.Platform

/**
 * The <code>Actor</code> object provides functions for the definition of
 * actors, as well as all actor operations, such as
 * <code>receive</code>, <code>react</code>, <code>reply</code>,
 * etc.
 *
 * @version 0.9.0
 * @author Philipp Haller
 */
object Actor {

  private[actors] val selfs = new java.util.WeakHashMap(16, 0.5f)

  /**
   * Returns the currently executing actor. Should be used instead
   * of <code>this</code> in all blocks of code executed by
   * actors.
   *
   * @return returns the currently executing actor.
   */
  def self: Actor = synchronized {
    val t = currentThread
    var a = selfs.get(t).asInstanceOf[Actor]
    if (a eq null) {
      a = new ActorProxy(t)
      selfs.put(t, a)
    }
    a
  }

  def actor(body: => Unit): Actor = synchronized {
    val actor = new Actor {
      def act() = body
    }
    actor.start()
    actor
  }

  /**
   * Creates an instance of a thread-based actor specifying a
   * channel which can be used for typed communication with other
   * actors.
   */
/*
  def actor[a](ch: Channel[a])(body: => Unit): Actor = synchronized {
    val actor = new Actor {
      def act() = body
    }
    ch.receiver = actor
    actor.start()
    actor
  }
*/

  def ? : Any = self.in.?

  def poll: Option[Any] = self.in.poll

  /**
   * Receives a message from the mailbox of
   * <code>self</code>. Blocks if no message matching any of the
   * cases of <code>f</code> can be received.
   *
   * @param f ...
   * @return  ...
   */
  def receive[a](f: PartialFunction[Any, a]): a =
    self.in.receive(f)

  /**
   * Receives a message from the mailbox of
   * <code>self</code>. Blocks at most <code>msec</code>
   * milliseconds if no message matching any of the cases of
   * <code>f</code> can be received. If no message could be
   * received the <code>TIMEOUT</code> action is executed if
   * specified.
   *
   * @param msec ...
   * @param f    ...
   * @return     ...
   */
  def receiveWithin[R](msec: long)(f: PartialFunction[Any, R]): R =
    self.in.receiveWithin(msec)(f)

  /**
   * <code>receive</code> for event-based reactors.
   *
   * Actions in <code>f</code> have to contain the rest of the
   * computation of <code>self</code>, as this method will never
   * return.
   *
   * @param f ...
   * @return  ...
   */
  def react(f: PartialFunction[Any, Unit]): Nothing =
    self.in.react(f)

  /**
   * <code>receiveWithin</code> for event-based reactors.
   *
   * Actions in <code>f</code> have to contain the rest of the
   * computation of <code>self</code>, as this method will never
   * return.
   *
   * @param msec ...
   * @param f    ...
   * @return     ...
   */
  def reactWithin(msec: long)(f: PartialFunction[Any, Unit]): Nothing =
    self.in.reactWithin(msec)(f)

  def eventloop(f: PartialFunction[Any, Unit]): Nothing =
    self.in.react(new RecursiveProxyHandler(self, f))

  private class RecursiveProxyHandler(a: Actor, f: PartialFunction[Any, Unit])
          extends PartialFunction[Any, Unit] {
    def isDefinedAt(m: Any): boolean =
      true // events are immediately removed from the mailbox
    def apply(m: Any): Unit = {
      if (f.isDefinedAt(m)) f(m)
      self.in.react(this)
    }
  }

  /**
   * <p>Used for receiving a message from a specific actor.</p>
   * <p>Example:</p> <code>from (a) receive { //... }</code>
   *
   * @param r ...
   * @return  ...
   */
  def from(r: Actor): FromReceive =
    new FromReceive(r)

  private[actors] class FromReceive(r: Actor) {
    def receive[a](f: PartialFunction[Any, a]): a =
      self.in.receiveFrom(r)(f)
  }

  /**
   * Returns the actor which sent the last received message.
   */
  def sender: Actor = self.sender

  /**
   * Send <code>msg</code> to the actor waiting in a call to
   * <code>!?</code>.
   */
  def reply(msg: Any): Unit = sender.reply ! msg

  /**
   * Send <code>()</code> to the actor waiting in a call to
   * <code>!?</code>.
   */
  def reply(): Unit = reply(())

  private[actors] trait Body[a] {
    def orElse[b >: a](other: => b): b
    def andThen[b >: a](other: => b): b
  }

  implicit def mkBody[a](body: => a) = new Body[a] {
    def orElse[b >: a](other: => b): b = choose(body, other)
    def andThen[b >: a](other: => b): b = seq(body, other)
  }

  private[actors] def choose[a, b >: a](alt1: => a, alt2: => b): b = {
    val s = self
    // save former custom suspendActor function
    // (e.g. from further orElse)
    val suspendNext = s.suspendActor
    val detachNext = s.detachActor

    // have to get out of the point of suspend in alt1's
    // receive
    s.suspendActor = () => {
      s.in.isSuspended = false
      s.in.waitingFor = s.in.waitingForNone
      throw new SuspendActorException
    }
    s.detachActor = f => {
      s.in.waitingFor = s.in.waitingForNone
      Scheduler.unPendReaction
      throw new SuspendActorException
    }

    try {
      val res = alt1
      s.suspendActor = suspendNext
      s.detachActor = detachNext
      res
    }
    catch {
      case d: SuspendActorException =>
        s.suspendActor = suspendNext
        s.detachActor = detachNext
        alt2
    }
  }

  /**
   * Causes <code>self</code> to repeatedly execute
   * <code>body</code>.
   *
   * @param body ...
   */
  def loop(body: => Unit): Unit = {
    val s = self
    s.kill = () => { body; s.kill() }
    body
  }

  /**
   * Causes <code>self</code> to execute <code>first</code>
   * followed by <code>next</code>.
   *
   * @param first ...
   * @param next  ...
   */
  def seq[a, b >: a](first: => a, next: => b): b = {
    val s = self
    val killNext = s.kill
    s.kill = () => { s.kill = killNext; next; s.kill() }
    first
  }

  /**
   * Links <code>self</code> to actor <code>to</code>.
   *
   * @param to ...
   * @return   ...
   */
  def link(to: Actor): Actor = self.link(to)

  /**
   * Links <code>self</code> to actor defined by <code>body</code>.
   *
   * @param body ...
   * @return     ...
   */
  def link(body: => Unit): Actor = self.link(body)

  /**
   * Unlinks <code>self</code> from actor <code>from</code>.
   *
   * @param from ...
   */
  def unlink(from: Actor): Unit = self.unlink(from)

  /**
   * <p>
   *   Terminates execution of <code>self</code> with the following
   *   effect on linked actors:
   * </p>
   * <p>
   *   For each linked actor <code>a</code> with
   *   <code>trapExit</code> set to <code>true</code>, send message
   *   <code>Exit(self, reason)</code> to <code>a</code>.
   * </p>
   * <p>
   *   For each linked actor <code>a</code> with
   *   <code>trapExit</code> set to <code>false</code> (default),
   *   call <code>a.exit(reason)</code> if
   *   <code>!reason.equals("normal")</code>.
   * </p>
   */
  def exit(reason: String): Unit = self.exit(reason)
}

case class Request[a](msg: a) {
  private[actors] val in = new Channel[a]
  def reply(resp: a): unit = {
    in ! resp
  }
}

/**
 * <p>
 *   This class provides (together with <code>Channel</code>) an
 *   implementation of event-based actors.
 * </p>
 * <p>
 *   The main ideas of our approach are explained in the paper<br>
 *   <b>Event-Based Programming without Inversion of Control</b>,
 *   Philipp Haller, Martin Odersky <i>Proc. JMLC 2006</i>
 * </p>
 *
 * @version 0.9.0
 * @author Philipp Haller
 */
trait Actor extends OutputChannel[Any] {
  private[actors] val in = new Channel[Any]
  in.receiver = this

  private var rc: Channel[Any] = null

  private[actors] def reply: Channel[Any] = {
    if (rc eq null) {
      rc = new Channel[Any]
      rc.receiver = this
    }
    rc
  }

  private[actors] def freshReply(): Unit = {
    rc = new Channel[Any]
    rc.receiver = this
  }

  /**
   * The behavior of an actor is specified by implementing this
   * abstract method. Note that the preferred way to create actors
   * is through the <code>actor</code> method
   * defined in object <code>Actor</code>.
   */
  def act(): Unit

  /**
   * Sends <code>msg</code> to this actor (asynchronous).
   */
  def !(msg: Any): Unit = in ! msg

  def forward(msg: Any): Unit = in forward msg

  /**
   * Sends <code>msg</code> to this actor and awaits reply
   * (synchronous).
   */
  def !?(msg: Any): Any = in !? msg

  def rpc[a](msg: a): a = {
    Debug.info("Actor.!? called by "+Actor.self)
    val req = Request(msg)
    in ! req
    req.in.?
  }

  private val lastSenders = new Stack[Actor]

  private[actors] def sender: Actor = {
    if (lastSenders.isEmpty) null
    else lastSenders.top
  }

  private[actors] def pushSender(s: Actor) = { lastSenders.push(s) }
  private[actors] def popSender(): Unit = { lastSenders.pop }

  private[actors] var continuation: PartialFunction[Any, Unit] = null
  private[actors] var timeoutPending = false
  private[actors] var isDetached = false
  private[actors] var isWaiting = false

  private[actors] def scheduleActor(f: PartialFunction[Any, Unit], msg: Any) =
    if ((f eq null) && (continuation eq null)) {
      // do nothing (timeout is handled instead)
    }
    else {
      val task = new Reaction(this,
                              if (f eq null) continuation else f,
                              msg)
      Scheduler execute task
    }

  private[actors] def tick(): Unit =
    Scheduler tick this

  private[actors] def defaultDetachActor: PartialFunction[Any, Unit] => Unit =
    (f: PartialFunction[Any, Unit]) => {
      continuation = f
      isDetached = true
      throw new SuspendActorException
    }

  private[actors] var suspendActor: () => Unit = _
  private[actors] var suspendActorFor: long => Unit = _
  private[actors] var resumeActor: () => Unit = _
  private[actors] var detachActor: PartialFunction[Any, Unit] => Unit = _
  private[actors] var kill: () => Unit = _

  private class ExitSuspendLoop extends Throwable

  private[actors] def resetActor(): Unit = {
    suspendActor = () => {
      isWaiting = true
      while(isWaiting) {
        try {
          wait()
        } catch {
          case t: InterruptedException =>
        }
      }
    }
    
    suspendActorFor = (msec: long) => {
      val ts = Platform.currentTime
      var waittime = msec
      var fromExc = false
      isWaiting = true

      try {
        while(isWaiting) {
          try {
            fromExc = false
            wait(waittime)
          } catch {
            case t: InterruptedException => {
              fromExc = true
              val now = Platform.currentTime
              val waited = now-ts
              waittime = msec-waited
              if (waittime < 0) { isWaiting = false }
            }
          }
          if (!fromExc) throw new ExitSuspendLoop
        }
      } catch { case _: ExitSuspendLoop => }
      Debug.info("leaving suspendActorFor("+msec+")")
    }

    resumeActor = () => { 
      isWaiting = false
      notify()
    }

    detachActor = defaultDetachActor

    kill = () => {}
  }

  resetActor()

  /**
   * Starts this actor.
   */
  def start(): Unit =
    Scheduler start new Reaction(this)


  /*
   * Debugging support.
   */
  private[actors] var name = ""

  private var childCnt = 0

  private[actors] def nextChildName = {
    val s = childCnt + name
    childCnt = childCnt + 1
    s
  }

  private[actors] def setName(n: String) =
    name = n

  private val links = new HashSet[Actor]

  /**
   * Links <code>self</code> to actor <code>to</code>.
   *
   * @param to ...
   * @return   ...
   */
  def link(to: Actor): Actor = {
    links += to
    to.linkTo(this)
    to
  }

  /**
   * Links <code>self</code> to actor defined by <code>body</code>.
   */
  def link(body: => Unit): Actor = {
    val actor = new Actor {
      def act() = body
    }
    link(actor)
    actor.start()
    actor
  }

  private[actors] def linkTo(to: Actor): Unit =
    links += to

  /**
   Unlinks <code>self</code> from actor <code>from</code>.
   */
  def unlink(from: Actor): Unit = {
    links -= from
    from.unlinkFrom(this)
  }

  private[actors] def unlinkFrom(from: Actor): Unit =
    links -= from

  var trapExit = false

  private[actors] var exitReason: String = ""

  /**
   * <p>
   *   Terminates execution of <code>self</code> with the following
   *   effect on linked actors:
   * </p>
   * <p>
   *   For each linked actor <code>a</code> with
   *   <code>trapExit</code> set to <code>true</code>, send message
   *   <code>Exit(self, reason)</code> to <code>a</code>.
   * </p>
   * <p>
   *   For each linked actor <code>a</code> with
   *   <code>trapExit</code> set to <code>false</code> (default),
   *   call <code>a.exit(reason)</code> if
   *   <code>!reason.equals("normal")</code>.
   * </p>
   */
  def exit(reason: String): Unit = {
    exitReason = reason
    currentThread.interrupt()
  }

  private[actors] def exit(from: Actor, reason: String): Unit = {
    if (from == this) {
      exit(reason)
    }
    else {
      if (trapExit)
        this ! Exit(from, reason)
      else if (!reason.equals("normal"))
        exit(reason)
    }
  }

  private[actors] def exitLinked(): Unit =
    exitLinked(exitReason, new HashSet[Actor])

  private[actors] def exitLinked(reason: String): Unit =
    exitLinked(reason, new HashSet[Actor])

  private[actors] def exitLinked(reason: String,
                                 exitMarks: HashSet[Actor]): Unit = {
    if (exitMarks contains this) {
      // we are marked, do nothing
    }
    else {
      exitMarks += this // mark this as exiting
      // exit linked processes
      val iter = links.elements
      while (iter.hasNext) {
        val linked = iter.next
        unlink(linked)
        linked.exit(this, reason)
      }
      exitMarks -= this

      // unregister in scheduler
      Scheduler terminated this
    }
  }
}


/**
 * Messages of this type are sent to each actor <code>a</code>
 * that is linked to an actor <code>b</code> whenever
 * <code>b</code> terminates and <code>a</code> has
 * <code>trapExit</code> set to <code>true</code>.
 *
 * @version 0.9.0
 * @author Philipp Haller
 */
case class Exit(from: Actor, reason: String)


/**
 * This class is used by our efficient message queue
 * implementation.
 */
private[actors] abstract class MessageQueueResult[Msg] {
  def msg: Msg
  def sender: Actor
}

/**
 * The class <code>MessageQueue</code> provides an efficient
 * implementation of a message queue specialized for this actor
 * library. Classes in this package are supposed to be the only
 * clients of this class.
 *
 * @author Martin Odersky, Philipp Haller
 */
private[actors] class MessageQueue[Msg] extends MessageQueueResult[Msg] {
  var msg: Msg = _
  var sender: Actor = _
  private var next: MessageQueue[Msg] = this

  def append(msg: Msg, sender: Actor) = {
    val q = new MessageQueue[Msg]
    q.msg = msg
    q.sender = sender
    q.next = next
    next = q
  }

  def extractFirst(p: Msg => boolean): MessageQueueResult[Msg] = {
    var q = this
    var qnext = q.next
    while (qnext != this) {
      if (p(qnext.msg)) {
        q.next = qnext.next
        return qnext
      }
      q = qnext
      qnext = qnext.next
    }
    null
  }

  def dequeueFirst(p: MessageQueueResult[Msg] => boolean): MessageQueueResult[Msg] = {
    var q = this
    var qnext = q.next
    while (qnext != this) {
      if (p(qnext)) {
        q.next = qnext.next
        return qnext
      }
      q = qnext
      qnext = qnext.next
    }
    null
  }
}
