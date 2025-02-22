/*
 * Copyright 2025 Calin-Andrei Burloiu
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

package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.businessync.BusinessyncEvent

import java.net.URI

sealed abstract class TrackSessionEvent extends BusinessyncEvent

case class TracksOpenedEvent(uri: URI, tracks: TrackSpecs) extends TrackSessionEvent

case class TracksClosedEvent(uri: Option[URI]) extends TrackSessionEvent

case class TracksUpdatedEvent(tracks: TrackSpecs) extends TrackSessionEvent

case class TrackAddedEvent(track: TrackSpec, beforeId: Option[TrackSpec.Id]) extends TrackSessionEvent

case class TrackUpdatedEvent(track: TrackSpec) extends TrackSessionEvent

case class TrackMovedEvent(movedId: TrackSpec.Id, beforeId: Option[TrackSpec.Id]) extends TrackSessionEvent

case class TrackRemovedEvent(id: TrackSpec.Id) extends TrackSessionEvent
