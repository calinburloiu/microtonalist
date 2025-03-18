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
 * Default implementation of the `RepoSelector` trait for selecting repositories based on URI schemes.
 *
 * @param fileRepo      Optional repository instance for handling URIs with the `file` scheme or no scheme.
 * @param httpRepo      Optional repository instance for handling URIs with the `http` or `https` scheme.
 * @param libraryRepo   Optional repository instance for handling URIs with the `microtonalist` scheme.
 * @param onNoRepoFound A function defining the behavior when no repository is found for a given URI.
 *                      Defaults to throwing an `IllegalArgumentException`.
 * @tparam R The type of the repositories being selected.
 */
class DefaultRepoSelector[R](fileRepo: Option[R],
                             httpRepo: Option[R],
                             libraryRepo: Option[R],
                             onNoRepoFound: URI => Throwable = DefaultRepoSelector.DefaultOnNoRepoFound)
  extends RepoSelector[R] {

  override def selectRepo(uri: URI): Option[R] = uri.getScheme match {
    case null | UriScheme.File => fileRepo
    case UriScheme.Http | UriScheme.Https => httpRepo
    case UriScheme.MicrotonalistLibrary => libraryRepo
    case _ => None
  }

  override def selectRepoOrThrow(uri: URI): R = selectRepo(uri).getOrElse {
    throw onNoRepoFound(uri)
  }
}

private object DefaultRepoSelector {
  private val DefaultOnNoRepoFound: URI => Throwable = { uri =>
    new IllegalArgumentException(s"No repo found for $uri")
  }
}
