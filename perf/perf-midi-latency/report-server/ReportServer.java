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

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import com.sun.net.httpserver.SimpleFileServer.OutputLevel;

/**
 * Serves the latency report of the module directory given as the first argument, on the port given as the second:
 * {@code report/} at the root and {@code results/} at {@code /results/}, where the report reads it. Run it with
 * {@code bin/mtlist-perf-midi-latency serve}.
 */
void main(String[] args) throws IOException {
  Path module = Path.of(args[0]).toAbsolutePath().normalize();
  int port = Integer.parseInt(args[1]);

  HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
  var log = SimpleFileServer.createOutputFilter(System.out, OutputLevel.INFO);
  server.createContext("/", SimpleFileServer.createFileHandler(module.resolve("report"))).getFilters().add(log);
  server.createContext("/results/", SimpleFileServer.createFileHandler(module.resolve("results"))).getFilters().add(log);
  server.start();
  IO.println("Serving the latency report at http://localhost:" + port + "/ (Ctrl-C to stop)");
}
