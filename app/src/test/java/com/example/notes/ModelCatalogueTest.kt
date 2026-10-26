package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading OpenRouter's answers, using payloads captured from it.
 *
 * The point of these is the **nulls**. Everything the picker shows can be missing: the
 * Intelligence Index is on 286 of 467 models, `max_prompt_tokens` is null on every endpoint of
 * this app's own default model, and the throughput statistic is a percentile object on a busy
 * endpoint, a flat number elsewhere, and absent when nothing has run. If any of those were
 * flattened to `0` the screen would say "this provider has stopped", which is a different and
 * much more alarming statement than "OpenRouter does not say".
 */
class ModelCatalogueTest {

    // Captured from /api/v1/models on 2026-10-25, trimmed to the fields the app reads.
    private val models = """
        { "data": [
          { "id": "other/model", "name": "Something Else", "created": 1700000000,
            "context_length": 128000,
            "architecture": { "input_modalities": ["text"], "output_modalities": ["text"] },
            "top_provider": { "max_completion_tokens": 4096 } },
          { "id": "deepseek/deepseek-v4.1-flash-1017",
            "name": "DeepSeek: DeepSeek V4.1 Flash 1017",
            "created": 1792225624,
            "context_length": 1048576,
            "architecture": { "modality": "text+image->text",
              "input_modalities": ["text", "image"],
              "output_modalities": ["text"] },
            "top_provider": { "context_length": 1048576,
                              "max_completion_tokens": 393216,
                              "is_moderated": false },
            "reasoning": { "mandatory": false, "default_enabled": true,
                           "supported_efforts": ["max", "high", "low", "none"],
                           "default_effort": "high" },
            "benchmarks": { "artificial_analysis": { "intelligence_index": 39.5,
                                                     "coding_index": null,
                                                     "agentic_index": null } } }
        ] }
    """.trimIndent()

    // Captured from /api/v1/models/deepseek/deepseek-v4.1-flash-1017/endpoints, same day.
    private val endpoints = """
        { "data": { "id": "deepseek/deepseek-v4.1-flash-1017",
          "endpoints": [
            { "name": "DeepInfra | deepseek/deepseek-v4.1-flash-20261017",
              "provider_name": "DeepInfra", "tag": "deepinfra/fp8",
              "context_length": 1048576, "max_completion_tokens": 131072,
              "max_prompt_tokens": null, "quantization": "fp8", "status": 0,
              "pricing": { "prompt": "0.0000000784", "completion": "0.0000002352",
                           "input_cache_read": "0.000000002352", "discount": 0.51 },
              "latency_last_30m": null, "throughput_last_30m": null,
              "uptime_last_30m": null,
              "supported_parameters": ["tools", "tool_choice", "reasoning_effort"] },
            { "name": "OpenInference | deepseek/deepseek-v4.1-flash-20261017",
              "provider_name": "OpenInference", "tag": "openinference",
              "context_length": 1048576, "max_completion_tokens": 943718,
              "pricing": { "prompt": "0.00000002", "completion": "0.000001",
                           "input_cache_read": "0.000000015" },
              "latency_last_30m": { "p50": 874, "p75": 1494.25 },
              "throughput_last_30m": { "p50": 94, "p75": 64, "p90": 24, "p99": 6 },
              "uptime_last_30m": 0.98,
              "supported_parameters": ["tools"] },
            { "name": "DeepSeek | deepseek/deepseek-v4.1-flash-20261017",
              "provider_name": "DeepSeek", "tag": "deepseek",
              "context_length": 1048576, "max_completion_tokens": 393216,
              "pricing": { "prompt": "0.00000015", "completion": "0.0000006",
                           "input_cache_read": "0.000000003" },
              "supported_parameters": ["tools", "tool_choice"] }
          ] } }
    """.trimIndent()

    @Test
    fun `the catalogue finds one model and says when it is missing`() {
        val found = OpenRouterCatalogue.parseModels(models, "deepseek/deepseek-v4.1-flash-1017")
            .getOrThrow()

        assertEquals("the model's own name", "DeepSeek: DeepSeek V4.1 Flash 1017", found.name)
        assertEquals("released", 1792225624L, found.releasedAt)
        assertEquals("a million tokens of context", 1048576, found.contextLength)
        assertEquals("and the top provider's reply", 393216, found.maxOutput)

        // The add form's whole job: does OpenRouter have the id the person typed?
        val missing = OpenRouterCatalogue.parseModels(models, "deepseek/no-such-model")
        assertTrue("an unknown id is a failure", missing.isFailure)
        assertTrue(
            "and says so",
            missing.exceptionOrNull()!!.message!!.contains("no model"),
        )
    }

    @Test
    fun `modalities are read separately because the agent needs the inputs to have a gui pane`() {
        val found = OpenRouterCatalogue.parseModels(models, "deepseek/deepseek-v4.1-flash-1017")
            .getOrThrow()

        assertEquals(listOf("text", "image"), found.inputModalities)
        assertEquals(listOf("text"), found.outputModalities)
    }

    @Test
    fun `the effort ladder and its default come from the model not from us`() {
        val found = OpenRouterCatalogue.parseModels(models, "deepseek/deepseek-v4.1-flash-1017")
            .getOrThrow()

        assertEquals(listOf("max", "high", "low", "none"), found.efforts)
        assertEquals("high", found.defaultEffort)
    }

    @Test
    fun `a model with no intelligence index has none rather than zero`() {
        // 181 of the 467 models in the catalogue have no index. A `0` would rank them bottom of
        // a list they were never measured on.
        val scored = OpenRouterCatalogue.parseModels(models, "deepseek/deepseek-v4.1-flash-1017")
            .getOrThrow()
        val unscored = OpenRouterCatalogue.parseModels(models, "other/model").getOrThrow()

        assertEquals(39.5, scored.intelligence!!, 0.001)
        assertNull("not zero — nothing", unscored.intelligence)
        assertTrue("and no effort ladder either", unscored.efforts.isEmpty())
    }

    @Test
    fun `prices are shown per million tokens`() {
        // The API gives dollars per token as a string. `0.0000000784` in a column is a number
        // nobody can compare at a glance, so the conversion happens once, here.
        assertEquals(0.0784, OpenRouterCatalogue.perMillion("0.0000000784")!!, 1e-9)
        assertEquals(0.2352, OpenRouterCatalogue.perMillion("0.0000002352")!!, 1e-9)
        assertEquals("a free endpoint is zero, which is a real price", 0.0,
            OpenRouterCatalogue.perMillion("0")!!, 0.0)
        assertNull("and a missing one is missing", OpenRouterCatalogue.perMillion(""))
    }

    @Test
    fun `every endpoint of a model is kept separately because they are not interchangeable`() {
        // On one model DeepSeek reports a 393,216-token reply and DeepInfra 131,072. Hiding two
        // of the three DeepInfra tiers would be picking for the operator without saying so.
        val list = OpenRouterCatalogue.parseEndpoints(endpoints).getOrThrow()

        assertEquals(3, list.size)
        assertEquals(listOf("deepinfra/fp8", "openinference", "deepseek"), list.map { it.tag })
        assertEquals(
            "the tag is the identity, the name is what a person reads",
            "DeepInfra",
            list.first { it.tag == "deepinfra/fp8" }.providerName,
        )
    }

    @Test
    fun `a percentile statistic is read at its median and a flat number is read as itself`() {
        // This is the shape that made "take .p50" necessary: one endpoint answers with an object
        // and another with a number, and a reader that assumed either would show `—` for half
        // the catalogue.
        val list = OpenRouterCatalogue.parseEndpoints(endpoints).getOrThrow()
        val busy = list.first { it.tag == "openinference" }

        assertEquals(94.0, busy.tokensPerSecond!!, 0.001)
        assertEquals("a flat number is read as itself", 0.98, busy.uptime!!, 0.001)
    }

    @Test
    fun `a quiet endpoint has no throughput rather than zero`() {
        // `throughput_last_30m: null` means OpenRouter has seen no traffic, not that the
        // provider is stopped. This is the exact cell that would read as an outage.
        val quiet = OpenRouterCatalogue.parseEndpoints(endpoints).getOrThrow()
            .first { it.tag == "deepinfra/fp8" }

        assertNull("no throughput", quiet.tokensPerSecond)
        assertNull("no latency", quiet.uptime)
        assertNull("and no max prompt length either", quiet.maxPrompt)
    }

    @Test
    fun `tool support is read per endpoint because a model can have both`() {
        // A provider that cannot call a tool cannot run this agent at all, and the add form
        // greys those out. Reading it per model would have been wrong: it is a property of the
        // endpoint, and it can differ between endpoints of one model.
        val list = OpenRouterCatalogue.parseEndpoints(endpoints).getOrThrow()

        assertTrue("deepinfra declares tools", list.first { it.tag == "deepinfra/fp8" }.tools)
        assertTrue("so does deepseek", list.first { it.tag == "deepseek" }.tools)
        assertTrue("and openinference", list.first { it.tag == "openinference" }.tools)
    }

    @Test
    fun `an endpoint list with nothing in it is a failure not an empty screen`() {
        val empty = """{ "data": { "id": "x", "endpoints": [] } }"""

        val result = OpenRouterCatalogue.parseEndpoints(empty)

        assertTrue("nothing is not a list of nothing", result.isFailure)
        assertTrue(
            "and says why",
            result.exceptionOrNull()!!.message!!.contains("no providers"),
        )
    }

    @Test
    fun `the cache price is read too because it is a third of the cost of an idle agent`() {
        val list = OpenRouterCatalogue.parseEndpoints(endpoints).getOrThrow()

        assertEquals(0.002352, list.first { it.tag == "deepinfra/fp8" }.priceCache!!, 1e-9)
        assertFalse(
            "a provider with no cache price is not free to cache with",
            list.first { it.tag == "deepseek" }.priceCache == null,
        )
    }
}
