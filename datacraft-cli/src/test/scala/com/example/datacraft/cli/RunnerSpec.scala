package com.example.datacraft.cli

import java.io.ByteArrayOutputStream
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite

class RunnerSpec extends AnyFunSuite {

  test("runner executes built-in engine jobs by command name") {
    val output = new ByteArrayOutputStream()

    Console.withOut(output) {
      Runner.run(CommandLineArgs(command = "echo", parameters = Map("message" -> "hello")))
    }

    val text = output.toString(StandardCharsets.UTF_8)
    assert(text.contains("SUCCEEDED"))
    assert(text.contains("hello"))
  }

  test("runner starts embedded API for online engine access") {
    val server = Runner.startApi(CommandLineArgs(command = "serve-api", port = 0))
    try {
      val response = HttpClient
        .newHttpClient()
        .send(
          HttpRequest.newBuilder(server.uri("/health")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )

      assert(response.statusCode() == 200)
      assert(response.body().contains("\"status\":\"UP\""))
    } finally
      server.close()
  }
}
