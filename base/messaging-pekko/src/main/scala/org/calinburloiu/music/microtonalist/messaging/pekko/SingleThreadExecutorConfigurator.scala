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

package org.calinburloiu.music.microtonalist.messaging.pekko

import com.typesafe.config.Config
import org.apache.pekko.dispatch.{DispatcherPrerequisites, ExecutorServiceConfigurator, ExecutorServiceFactory}

import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}

/** Gives a Pekko dispatcher a thread of its own, the UI mailbox's default. */
final class SingleThreadExecutorConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends ExecutorServiceConfigurator(config, prerequisites) {

  override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory =
    // Not a lambda: `createExecutorService` has no parameter list, so `ExecutorServiceFactory` isn't a SAM.
    new ExecutorServiceFactory {
      override def createExecutorService: ExecutorService = Executors.newSingleThreadExecutor(threadFactory)
    }
}
