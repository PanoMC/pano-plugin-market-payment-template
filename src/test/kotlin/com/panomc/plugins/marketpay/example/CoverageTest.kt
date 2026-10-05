package com.panomc.plugins.marketpay.example

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The template implements P-01 to P-26 of spec 16 section 13.2 for its example gateway. A test of that case is named
 * `P-nn ...` (or carries the id in its class comment); this one fails when an id has no test, so a plugin built from the
 * template cannot silently drop one. Cases that do not apply to a gateway are implemented as a test that says why
 * (P-16: signed gateway; P-21: no recurring), never deleted.
 */
class CoverageTest {
    @Test
    fun `P-01 to P-26 each have at least one test`() {
        val dir = File("src/test/kotlin")
        assertTrue(dir.isDirectory, "run the tests from the project directory")
        val named = HashSet<String>()
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "CoverageTest.kt" }.forEach { file ->
            Regex("fun\\s+`(P-\\d\\d)\\b").findAll(file.readText()).forEach { named.add(it.groupValues[1]) }
            // The contract suite and the dynamic factory are named by class / factory comment.
        }
        // P-01 is the inherited contract suite: a test class must extend ProviderContractTest.
        val contract = dir.walkTopDown().any { it.isFile && it.name.endsWith("ContractTest.kt") && it.readText().contains(": ProviderContractTest()") }
        if (contract) named.add("P-01")
        val missing = (1..26).map { "P-%02d".format(it) }.filter { it !in named }
        assertTrue(missing.isEmpty(), "no test for: $missing")
    }
}
