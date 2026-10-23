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

package org.calinburloiu.music.microtonalist.messaging

/**
 * A message that asks the Business Thread to do something, run by the one handler registered for its class or for a
 * supertype of it.
 */
trait Command

/**
 * A [[Command]] whose handler replies with a value of type `R`, which [[MessageBus.ask]] returns.
 *
 * @tparam R the type of the reply
 */
trait Request[R] extends Command
