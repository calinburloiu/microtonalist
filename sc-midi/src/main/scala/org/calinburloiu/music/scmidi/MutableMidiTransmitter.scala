/*
 * Copyright 2026 Calin-Andrei Burloiu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.calinburloiu.music.scmidi

import javax.annotation.concurrent.NotThreadSafe

/**
 * A [[MidiTransmitter]] whose receivers change in place, for use from a single thread.
 *
 * Every change — [[receivers_=]], [[addReceiver]], [[addReceivers]], [[removeReceiver]], [[clearReceivers]] — is
 * `final` and has the same two-part shape: it runs inside [[withChangeGuard]] and performs the change through
 * [[setReceivers]] and nothing else. That leaves a subclass exactly two extension points, which it can use
 * independently:
 *
 *   - [[setReceivers]] — what to do when the receivers change. A subclass overriding it observes every change, from
 *     every entry point, and reads the current sequence with [[receivers]] before replacing it.
 *   - [[withChangeGuard]] — how a change is made atomic. The default runs the change directly;
 *     [[ConcurrentMidiTransmitter]] takes a write lock around it.
 *
 * Because the guard is the outer of the two and both are chosen by the class rather than by the caller, a subclass
 * overriding [[setReceivers]] cannot end up outside its own guard, whichever modifier — or direct assignment — was
 * used. The constructor stores `initialReceivers` directly, through neither hook, so that an override never runs on a
 * partially constructed object.
 *
 * Not thread-safe: use [[ConcurrentMidiTransmitter]] when several threads read or change the receivers.
 *
 * @param initialReceivers the receivers messages are forwarded to at construction; defaults to none.
 */
@NotThreadSafe
class MutableMidiTransmitter(initialReceivers: Seq[MidiReceiver] = Seq.empty) extends MidiTransmitter {
  private var _receivers: Seq[MidiReceiver] = initialReceivers

  override def receivers: Seq[MidiReceiver] = _receivers

  /**
   * Replaces all receivers.
   *
   * @param newReceivers the receivers messages are forwarded to from now on, in order.
   */
  final def receivers_=(newReceivers: Seq[MidiReceiver]): Unit = withChangeGuard {
    setReceivers(newReceivers)
  }

  /**
   * Appends a receiver. The same receiver may be added more than once.
   *
   * @param receiver the receiver to append.
   */
  final def addReceiver(receiver: MidiReceiver): Unit = withChangeGuard {
    setReceivers(_receivers :+ receiver)
  }

  /**
   * Appends receivers, in order.
   *
   * @param newReceivers the receivers to append.
   */
  final def addReceivers(newReceivers: Seq[MidiReceiver]): Unit = withChangeGuard {
    setReceivers(_receivers :++ newReceivers)
  }

  /**
   * Removes every occurrence of a receiver; does nothing when it is absent.
   *
   * @param receiver the receiver to remove.
   */
  final def removeReceiver(receiver: MidiReceiver): Unit = withChangeGuard {
    setReceivers(_receivers.filterNot(_ == receiver))
  }

  /** Removes all receivers. */
  final def clearReceivers(): Unit = withChangeGuard {
    setReceivers(Seq.empty)
  }

  /**
   * Runs a change, and the read of the current receivers that computes it, as one unit.
   *
   * Every modifier of this class wraps itself in this method, so an override makes the whole read-modify-write atomic
   * rather than the assignment alone. The default runs `body` directly, this class being single-threaded.
   *
   * @param body the change to run.
   * @return whatever `body` returns.
   */
  protected def withChangeGuard[R](body: => R): R = body

  /**
   * Performs a change of the receivers. The single point every modifier and [[receivers_=]] funnel through, and the
   * one to override to observe or extend what happens when the receivers change; always called inside
   * [[withChangeGuard]].
   *
   * An override may read [[receivers]] to compare the incoming sequence with the current one, and must call
   * `super.setReceivers` for the change to take effect.
   *
   * @param newReceivers the receivers messages are forwarded to from now on, in order.
   */
  protected def setReceivers(newReceivers: Seq[MidiReceiver]): Unit = {
    _receivers = newReceivers
  }
}
