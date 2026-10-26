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

package org.calinburloiu.music.microtonalist.testbench.driver

import javafx.scene.input.{Clipboard, ClipboardContent, MouseButton}
import javafx.stage.{Stage, Window}
import org.calinburloiu.music.microtonalist.testbench.driver.protocol.{DriverRequest, MouseButtonName}
import play.api.libs.json.{JsObject, Json}

import java.util.Base64
import scala.jdk.CollectionConverters.*

/**
 * Carries out the server's requests in the app under test.
 *
 * @param fx runs the work on the JavaFX Application Thread
 * @param input injects the clicks, keys and text
 */
final class RequestHandler(fx: FxExecutor, input: Input) {
  /**
   * Carries out a request and returns its result, as [[DriverRequest]] describes it for each request.
   *
   * An input request answers once the Application Thread has handled the input, or once it waits in a nested event
   * loop, such as a dialog's `showAndWait`. A snapshot waits for the Application Thread first; it is of the selector's
   * node if given, else of the window given, else of the first stage, at a scale in (0, 4].
   *
   * @throws DriverException if the request fails
   */
  def handle(request: DriverRequest): JsObject = request match {
    case DriverRequest.Windows => fx(SceneReader.windows())
    case DriverRequest.Dump(window) => fx(SceneReader.dump(window))
    case DriverRequest.Snapshot(window, selector, scale) =>
      if (!(scale > 0 && scale <= RequestHandler.MaxScale)) {
        throw DriverException(s"the scale must be in (0, ${RequestHandler.MaxScale.toInt}], not $scale")
      }
      fx.idle()
      fx {
        val image = selector.fold(Snapshots.window(snapshotWindow(window), scale)) { selector =>
          Snapshots.node(Selectors.find(selector, window).node, scale)
        }
        Json.obj(
          "png" -> Base64.getEncoder.encodeToString(Snapshots.png(image)),
          "width" -> image.getWidth.toInt,
          "height" -> image.getHeight.toInt,
        )
      }
    case DriverRequest.Click(selector, window, button, clickCount) =>
      if (clickCount < 1) {
        throw DriverException(s"the click count must be at least 1, not $clickCount")
      }
      afterIdle {
        fx {
          val found = Selectors.find(selector, window)
          val answer = Json.obj(
            "matchedBy" -> found.matchedBy.wireName,
            "node" -> SceneReader.describe(found.node),
            "disabled" -> found.node.isDisabled,
          )
          input.click(found.node, mouseButton(button), clickCount)
          answer
        }
      }
    case DriverRequest.Key(key) =>
      val chord = KeyChord.parse(key, RequestHandler.IsMac).fold(error => throw DriverException(error), identity)
      afterIdle {
        fx(input.key(chord))
        Json.obj()
      }
    case DriverRequest.Type(text) => afterIdle {
        fx(input.typeText(text))
        Json.obj()
      }
    case DriverRequest.Idle => afterIdle(Json.obj())
    case DriverRequest.ReadClipboard => Json.obj("text" -> fx(Option(Clipboard.getSystemClipboard.getString)))
    case DriverRequest.WriteClipboard(text) =>
      fx {
        val content = ClipboardContent()
        content.putString(text)
        Clipboard.getSystemClipboard.setContent(content)
      }
      Json.obj()
  }

  private def afterIdle(answer: => JsObject): JsObject = {
    val result = answer
    fx.idle()
    result
  }

  /** The window at the given index, or else the first stage. */
  private def snapshotWindow(window: Option[Int]): Window = window.fold {
    Window.getWindows.asScala.collectFirst { case stage: Stage => stage }
      .getOrElse(throw DriverException("no stage shows"))
  }(SceneReader.window)

  private def mouseButton(name: MouseButtonName): MouseButton = name match {
    case MouseButtonName.Primary => MouseButton.PRIMARY
    case MouseButtonName.Secondary => MouseButton.SECONDARY
  }
}

private object RequestHandler {
  /** Whether the app runs on macOS, where `Shortcut` means Command. */
  val IsMac: Boolean = System.getProperty("os.name").startsWith("Mac")

  /** The largest scale that a snapshot may take. */
  val MaxScale: Double = 4
}
