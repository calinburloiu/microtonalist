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

package org.calinburloiu.music.microtonalist.testbench.qa

import play.api.libs.json.*

/**
 * A QA device of the rig, i.e. a virtual MIDI port of the run, named `qa:<name>` in the rig the agent wrote.
 *
 * @param input whether a track uses it as its input, so that the server sends MIDI to it
 * @param output whether a track uses it as its output, so that the server records the MIDI it receives
 */
final case class QaDevice(name: String, input: Boolean, output: Boolean) {
  /** The name of the device's virtual port in the run `runId`, as `sendmidi` and `receivemidi` open it. */
  def port(runId: String): String = s"$runId-$name"
}

/**
 * A rig rewritten for a run.
 *
 * @param json the rig, with each QA device replaced by the run's port
 * @param devices the QA devices, in their order of first use
 */
final case class RewrittenRig(json: JsValue, devices: Seq[QaDevice])

/**
 * Rewrites a rig as a JSON tree, so that its tracks use the run's virtual ports, and refuses a rig that could reach a
 * real MIDI device.
 */
object RigRewriter {
  private val QaPrefix: String = "qa:"
  private val CoreMidi4jPrefix: String = "CoreMIDI4J - "
  private val PortVendor: String = "<Unknown Manufacturer>"
  private val DeviceNamePattern: String = "[A-Za-z0-9_-]{1,32}"
  private val MidiDeviceIdKey: String = "midiDeviceId"
  private val RefKey: String = "$ref"
  private val InputKey: String = "input"
  private val OutputKey: String = "output"

  private type Walked = Either[String, (JsValue, Seq[QaDevice])]

  /**
   * Replaces each QA device of `rig` with the port of the run `runId`, leaving the rest of the JSON unchanged, an
   * invalid rig included.
   *
   * A `midiDeviceId` names a QA device when its `name` is `qa:<device>`, or `CoreMIDI4J - <runId>-<device>`, which an
   * earlier rewrite for the same run wrote, `<device>` matching `[A-Za-z0-9_-]{1,32}`.
   *
   * @return the rewritten rig; or, naming the JSON path, an error for any other `midiDeviceId`, for a QA device
   *   elsewhere than a track's `input` or `output`, and for any `$ref`, which could hide a device
   */
  def rewrite(rig: JsValue, runId: String): Either[String, RewrittenRig] = {
    walk(rig, JsPath, runId).map { case (json, uses) => RewrittenRig(json, merge(uses)) }
  }

  private def walk(value: JsValue, path: JsPath, runId: String): Walked = value match {
    case obj: JsObject if obj.keys.contains(RefKey) =>
      Left(s"${path \ RefKey}: a $RefKey isn't allowed, since it could hide a MIDI device")
    case obj: JsObject =>
      walkAll(obj.fields) { case (key, fieldValue) =>
        val fieldPath = path \ key
        val walked = if (key == MidiDeviceIdKey) {
          rewriteDevice(fieldValue, fieldPath, runId)
        } else {
          walk(fieldValue, fieldPath, runId)
        }
        walked.map { case (json, devices) => (key -> json, devices) }
      }.map { case (fields, devices) => (JsObject(fields), devices) }
    case JsArray(items) =>
      walkAll(items.zipWithIndex) { case (item, index) =>
        walk(item, path(index), runId)
      }.map { case (newItems, devices) => (JsArray(newItems), devices) }
    case other => Right((other, Seq.empty))
  }

  /** Walks each element with `f`, stopping at the first error, and concatenates the devices found. */
  private def walkAll[A, B](elements: collection.Seq[A])(f: A => Either[String, (B, Seq[QaDevice])])
      : Either[String, (Seq[B], Seq[QaDevice])] = {
    elements.foldLeft[Either[String, (Seq[B], Seq[QaDevice])]](Right((Vector.empty, Vector.empty))) {
      (acc, element) =>
        for {
          (results, devices) <- acc
          (result, newDevices) <- f(element)
        } yield (results :+ result, devices ++ newDevices)
    }
  }

  private def rewriteDevice(value: JsValue, path: JsPath, runId: String): Walked = {
    // Walking the value first refuses a `$ref` inside it, which the replacement would otherwise drop unchecked.
    walk(value, path, runId).flatMap { _ =>
      (qaDeviceName(value, runId), trackPortKey(path)) match {
        case (None, _) =>
          Left(s"$path: $value isn't a QA device; name it \"$QaPrefix<device>\", <device> matching " +
            DeviceNamePattern)
        case (Some(_), None) =>
          Left(s"$path: a QA device can only be a track's $InputKey or $OutputKey")
        case (Some(name), Some(portKey)) =>
          val device = QaDevice(name, input = portKey == InputKey, output = portKey == OutputKey)
          Right((Json.obj("name" -> s"$CoreMidi4jPrefix${device.port(runId)}", "vendor" -> PortVendor), Seq(device)))
      }
    }
  }

  private def qaDeviceName(midiDeviceId: JsValue, runId: String): Option[String] = {
    val runPortPrefix = s"$CoreMidi4jPrefix$runId-"
    for {
      name <- (midiDeviceId \ "name").asOpt[String]
      prefix <- Seq(QaPrefix, runPortPrefix).find(name.startsWith)
      deviceName = name.drop(prefix.length)
      if deviceName.matches(DeviceNamePattern)
    } yield deviceName
  }

  /** `input` or `output` if `path` is that of a track's `input.midiDeviceId` or `output.midiDeviceId`. */
  private def trackPortKey(path: JsPath): Option[String] = path.path match {
    case List(KeyPathNode("tracks"), IdxPathNode(_), KeyPathNode(key), KeyPathNode(MidiDeviceIdKey))
        if key == InputKey || key == OutputKey => Some(key)
    case _ => None
  }

  /** Merges the uses of each device into one, in the order of their first use. */
  private def merge(uses: Seq[QaDevice]): Seq[QaDevice] = {
    uses.map(_.name).distinct.map { name =>
      val deviceUses = uses.filter(_.name == name)
      QaDevice(name, input = deviceUses.exists(_.input), output = deviceUses.exists(_.output))
    }
  }
}
