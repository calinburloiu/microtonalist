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

package org.calinburloiu.music.microtonalist.format

import java.net.URI

/**
 * A trait for selecting a repository instance based on a provided URI.
 *
 * @tparam R The type of the repository being selected.
 */
trait RepoSelector[R] {
  /**
   * Selects a repository instance based on the provided URI.
   *
   * @param uri The URI used to identify the repository instance.
   * @return An optional repository instance matching the given URI.
   */
  def selectRepo(uri: URI): Option[R]

  /**
   * Selects a repository instance corresponding to the provided URI or throws an exception if no repository is found.
   *
   * @param uri The URI used to identify the repository instance.
   * @return The repository instance matching the given URI.
   */
  def selectRepoOrThrow(uri: URI): R
}
