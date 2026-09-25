package com.example.datacraft.cli

import java.io.ByteArrayOutputStream
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite

class RunnerSpec extends AnyFunSuite {

  test("explicit CLI master overrides config while explicit job parameters take precedence") {
    val file = java.nio.file.Files.createTempFile("datacraft-config-", ".properties")
    try {
      java.nio.file.Files.writeString(file, "spark.master=local[3]\n")
      val defaults = CommandLineArgs(configFile = Some(file))
      assert(Runner.buildRequest(defaults, "noop").parameters().get("spark.master") == "local[3]")
      assert(
        Runner
          .buildRequest(defaults.copy(master = "local[*]", masterExplicit = true), "noop")
          .parameters()
          .get("spark.master") == "local[*]"
      )
      assert(
        Runner
          .buildRequest(
            defaults.copy(master = "local[2]", parameters = Map("spark.master" -> "local[4]")),
            "noop"
          )
          .parameters()
          .get("spark.master") == "local[4]"
      )
    } finally java.nio.file.Files.deleteIfExists(file)
  }

  test("structured output and result file contain the same job metrics") {
    val file   = java.nio.file.Files.createTempFile("datacraft-result-", ".json")
    val output = new ByteArrayOutputStream()
    try {
      Console.withOut(output) {
        Runner.run(
          CommandLineArgs(
            command = "echo",
            jsonOutput = true,
            resultFile = Some(file),
            parameters = Map("message" -> "a\"b\n中文")
          )
        )
      }
      val bytes = output.toByteArray
      assert(bytes.sameElements(java.nio.file.Files.readAllBytes(file)))
      val json = new String(bytes, StandardCharsets.UTF_8)
      assert(json.endsWith("}\n") && !json.contains("\r"))
      assert(json.contains("\"status\":\"SUCCEEDED\""))
      assert(json.contains("durationMillis"))
    } finally java.nio.file.Files.deleteIfExists(file)
  }

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
