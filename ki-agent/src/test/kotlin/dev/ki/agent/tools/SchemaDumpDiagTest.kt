package dev.ki.agent.tools

import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import dev.ki.agent.tools.builtin.BuiltinTools
import kotlin.test.Test

class SchemaDumpDiagTest {
    @Test
    fun `dump real tool schemas`() {
        val gen = OpenAICompatibleToolDescriptorSchemaGenerator()
        BuiltinTools.all().forEach { t ->
            println("===${t.descriptor.name}===")
            println(gen.generate(t.descriptor).toString())
        }
    }
}
