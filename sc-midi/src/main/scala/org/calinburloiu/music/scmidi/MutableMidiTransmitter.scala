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
 * Every modifier — [[addReceiver]], [[addReceivers]], [[removeReceiver]], [[clearReceivers]] — computes the new
 * sequence from the current one and assigns it through [[receivers_=]] and nothing else, so a subclass that overrides
 * the setter observes every change through that one method (see [[ConcurrentMidiTransmitter]]). To keep the funnel
 * the only re-entry point, modifiers read the backing field directly rather than through the public getter. The
 * constructor stores `initialReceivers` directly, without calling the setter, so that a subclass override never runs
 * on a partially constructed object.
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
   * Replaces all receivers. Every other modifier ends up here.
   *
   * @param newReceivers the receivers messages are forwarded to from now on, in order.
   */
  def receivers_=(newReceivers: Seq[MidiReceiver]): Unit = {
    _receivers = newReceivers
  }

  /**
   * Appends a receiver. The same receiver may be added more than once.
   *
   * @param receiver the receiver to append.
   */
  def addReceiver(receiver: MidiReceiver): Unit = {
    receivers = _receivers :+ receiver
  }

  /**
   * Appends receivers, in order.
   *
   * @param newReceivers the receivers to append.
   */
  def addReceivers(newReceivers: Seq[MidiReceiver]): Unit = {
    receivers = _receivers :++ newReceivers
  }

  /**
   * Removes every occurrence of a receiver; does nothing when it is absent.
   *
   * @param receiver the receiver to remove.
   */
  def removeReceiver(receiver: MidiReceiver): Unit = {
    receivers = _receivers.filterNot(_ == receiver)
  }

  /** Removes all receivers. */
  def clearReceivers(): Unit = {
    receivers = Seq.empty
  }

  /** No-op: this transmitter holds no resources. */
  override def close(): Unit = {}
}
