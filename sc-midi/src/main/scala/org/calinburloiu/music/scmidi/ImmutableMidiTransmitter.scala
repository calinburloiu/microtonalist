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

/**
 * A [[MidiTransmitter]] that is a value: every change returns a new instance and leaves this one untouched.
 *
 * Suited to configuration that is built once and then only read, and to pipelines owned by a single thread that
 * prefer to swap a whole transmitter rather than mutate one.
 *
 * @param receivers the receivers messages are forwarded to, in order; defaults to none.
 */
case class ImmutableMidiTransmitter(receivers: Seq[MidiReceiver] = Seq.empty) extends MidiTransmitter {

  /**
   * Returns a copy with `receiver` appended. The same receiver may appear more than once.
   *
   * @param receiver the receiver to append.
   * @return a new transmitter; this one is unchanged.
   */
  def withReceiver(receiver: MidiReceiver): ImmutableMidiTransmitter = copy(receivers = receivers :+ receiver)

  /**
   * Returns a copy with `newReceivers` appended, in order.
   *
   * @param newReceivers the receivers to append.
   * @return a new transmitter; this one is unchanged.
   */
  def withReceivers(newReceivers: Seq[MidiReceiver]): ImmutableMidiTransmitter =
    copy(receivers = receivers :++ newReceivers)

  /**
   * Returns a copy without any occurrence of `receiver`. Returns an equal transmitter when it is absent.
   *
   * @param receiver the receiver to remove.
   * @return a new transmitter; this one is unchanged.
   */
  def withoutReceiver(receiver: MidiReceiver): ImmutableMidiTransmitter =
    copy(receivers = receivers.filterNot(_ == receiver))

  /**
   * Returns a copy without any occurrence of any of `receiversToRemove`.
   *
   * @param receiversToRemove the receivers to remove.
   * @return a new transmitter; this one is unchanged.
   */
  def withoutReceivers(receiversToRemove: Seq[MidiReceiver]): ImmutableMidiTransmitter =
    copy(receivers = receivers.filterNot(receiversToRemove.contains))

  /** No-op: an immutable transmitter holds no resources. */
  override def close(): Unit = {}
}
