package com.juan.habitos.ui.child

import com.juan.habitos.ui.state.ActivityState
import com.juan.habitos.ui.state.SuggestedActivityUiModel
import com.juan.habitos.ui.state.activityEmoji
import com.juan.habitos.ui.state.professionalActivityEmojiById
import org.junit.Assert.*
import org.junit.Test

class ActivityInstructionsPresentationTest {
    private fun parse(source: String): ActivityInstructionsPresentation = parseActivityInstructions(source).also {
        assertEquals(source, it.original)
        assertEquals("Every original character must belong to exactly one block", source, it.blocks.joinToString("") { block -> block.source })
    }

    @Test fun `materials and three steps become separate blocks`() {
        val source = "Materiales: Papel y lápices.\n\nPaso a paso:\n1. Elegí un color.\n2. Dibujá lo que quieras.\n3. Mirá tu dibujo."
        val result = parse(source)
        assertTrue(result.structured)
        val materials = result.blocks.filterIsInstance<InstructionBlock.Context>().single()
        assertEquals("Materiales", materials.heading)
        assertTrue(materials.text.contains("Papel y lápices."))
        assertEquals(listOf("1", "2", "3"), result.blocks.filterIsInstance<InstructionBlock.Step>().map { it.number })
        assertTrue(result.supportsStepNavigation)
    }

    @Test fun `professional activities with three or four steps keep every complete instruction`() {
        for (stepCount in 3..4) {
            val materials = "Hoja, lápiz y marcadores opcionales (si tenés ganas de usarlos)."
            val instructions = (1..stepCount).map { number ->
                "Consigna $number con un ejemplo completo: podés elegir la variante $number o inventar otra."
            }
            val source = "Materiales: $materials\n\nPasos:\n" +
                instructions.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n")

            val result = parse(source)

            assertEquals(materials, result.contexts.single().text.trim())
            assertEquals(instructions, result.steps.map { it.text.trim() })
            assertEquals(stepCount, result.steps.size)
        }
    }

    @Test fun `analytics needs prefix becomes materials card before immediate numbered steps`() {
        val source = "Necesitás: Una pelota blanda o un par de medias enrolladas.\n" +
            "1. Buscá un lugar seguro.\n" +
            "2. Hacé rodar la pelota por el piso.\n" +
            "3. Elegí el movimiento que puedas hacer."

        val result = parse(source)

        assertTrue(result.supportsStepNavigation)
        assertEquals("Materiales", result.contexts.single().heading)
        assertEquals("Una pelota blanda o un par de medias enrolladas.", result.contexts.single().text.trim())
        assertEquals(listOf("1", "2", "3"), result.steps.map { it.number })
    }

    @Test fun `analytics no materials sentence stays complete in materials card`() {
        val materials = "No necesitás materiales. La música es opcional. Si la usás, dejá el celular apoyado."
        val source = "$materials\n1. Mové las manos.\n2. Sumá otro movimiento.\n3. Repetí la secuencia."

        val result = parse(source)

        assertTrue(result.supportsStepNavigation)
        assertEquals(materials, result.contexts.single().text.trim())
        assertEquals(3, result.steps.size)
    }

    @Test fun `steps without materials do not fabricate a materials block`() {
        val result = parse("1) Mirá a tu alrededor.\n2) Elegí un objeto.")
        assertTrue(result.structured)
        assertTrue(result.blocks.none { it is InstructionBlock.Context })
        assertEquals(2, result.blocks.filterIsInstance<InstructionBlock.Step>().size)
    }

    @Test fun `explicitly no materials uses requested label without losing original evidence`() {
        for (text in listOf("No necesitás materiales", "No se necesitan materiales.", "No requiere materiales!", "No se requieren materiales.", "Ninguno.", "Ningún material")) {
            val result = parse("Materiales: $text\n\nPasos:\n1. Imaginá algo lindo.")
            assertEquals("No necesitás materiales", result.blocks.filterIsInstance<InstructionBlock.Context>().single().displayText)
        }
    }

    @Test fun `qualified materials statements are not summarized as requiring nothing`() {
        val source = "Materiales: No necesitás materiales especiales; buscá una hoja.\n\n1. Dibujá."
        val result = parse(source)
        val materials = result.blocks.filterIsInstance<InstructionBlock.Context>().single()
        assertEquals(materials.text, materials.displayText)
        assertTrue(materials.displayText.contains("buscá una hoja."))
    }

    @Test fun `other person remains participants not physical materials`() {
        val result = parse("Participantes: Otra persona que quiera jugar con vos.\n\nPreparación: Busquen un lugar cómodo.\n\nPasos:\n1. Inventen una historia.")
        val context = result.blocks.filterIsInstance<InstructionBlock.Context>()
        assertEquals(listOf("Participantes", "Preparación"), context.map { it.heading })
        assertTrue(context.first().text.contains("Otra persona"))
        assertFalse(context.any { it.heading == "Materiales" })
    }

    @Test fun `all supported context headings keep their own identity`() {
        for (heading in listOf("MATERIALES", "Preparación", "Preparacion", "Antes de empezar", "Participantes")) {
            val result = parse("$heading:\nContenido explícito.\n\nConsignas:\nPaso 1: Leé todo.")
            assertEquals(heading, result.blocks.filterIsInstance<InstructionBlock.Context>().single().heading)
        }
    }

    @Test fun `multiline steps preserve CRLF paragraphs accents indentation and trailing text`() {
        val first = "Pensá una idea.\r\n  Podés usar música.\r\n\r\nLeé con calma.\r\n"
        val last = "Seguí explorando.\r\n\r\nNota: ¡No hay apuro!\r\n  "
        val result = parse("  Un comienzo.\r\n\r\nConsignas:\r\n1. $first" + "2) $last")
        val steps = result.blocks.filterIsInstance<InstructionBlock.Step>()
        assertEquals(first, steps[0].text)
        assertEquals(last, steps[1].text)
        assertTrue(result.blocks.first() is InstructionBlock.Prose)
    }

    @Test fun `more than three steps with mixed explicit markers are supported`() {
        val result = parse("Paso a paso:\n" + (1..60).joinToString("\n") { number -> when (number % 3) {
            0 -> "Paso $number: Explorá esta idea."
            1 -> "$number. Explorá esta idea."
            else -> "$number) Explorá esta idea."
        } })
        assertEquals(60, result.blocks.filterIsInstance<InstructionBlock.Step>().size)
        assertEquals("60", result.blocks.filterIsInstance<InstructionBlock.Step>().last().number)
    }

    @Test fun `legacy descriptions and unknown syntax fall back to exact complete text`() {
        for (source in listOf("Elegí colores y creá un dibujo libre.", "## Consigna\n• Primero dibujá.\n• Después pintá.", "", "   \n\t", "[1] Una idea\n[2] Otra idea")) {
            val result = parse(source)
            assertFalse(result.structured)
            assertFalse(result.supportsStepNavigation)
            assertEquals(listOf(InstructionBlock.Prose(source)), result.blocks)
        }
    }

    @Test fun `numbers inside text years and decimals are not step boundaries`() {
        val text = "Usá 2 colores y esperá 10 segundos.\n1.5 litros no es un paso.\n2026. Un año, no un paso."
        assertFalse(parse(text).structured)
        val result = parse("1. $text\n2. Volvé cuando quieras.")
        assertEquals(2, result.blocks.filterIsInstance<InstructionBlock.Step>().size)
        assertTrue(result.blocks.filterIsInstance<InstructionBlock.Step>().first().text.contains(text))
    }

    @Test fun `numbered materials list is not misclassified as activity steps`() {
        val result = parse("Materiales:\n1. Papel.\n2. Lápices.\n\nPasos:\n1. Hacé un dibujo.\n2. Elegí colores.")
        assertTrue(result.blocks.filterIsInstance<InstructionBlock.Context>().single().text.contains("2. Lápices."))
        assertEquals(2, result.blocks.filterIsInstance<InstructionBlock.Step>().size)
    }

    @Test fun `ambiguous material lists missing bodies gaps and duplicate numbering fall back intact`() {
        for (source in listOf(
            "Materiales:\n1. Papel.\n2. Lápices.",
            "Materiales:\n\n1. Papel.",
            "Materiales: Papel.\n1. Dibujá.",
            "2. Empezá por acá.", "1. Dibujá.\n3. Pintá.", "1. Dibujá.\n1. Pintá.",
            "Pasos:\n1. \n2. Leé.", "Pasos: Una consigna sin lista.", "Pasos:\nUna consigna sin lista.",
            "1. Dibujá.\nMateriales: Papel.",
            "Materiales: Papel.\n\n1. Dibujá.\n\nParticipantes: Otra persona.",
        )) assertFalse(source, parse(source).structured)
    }

    @Test fun `blank separated numbered steps may follow explicit context without a steps header`() {
        val result = parse("Antes de empezar:\nBuscá un lugar seguro.\n\n1. Movete despacio.\n2. Descansá.")
        assertTrue(result.structured)
        assertEquals(1, result.blocks.filterIsInstance<InstructionBlock.Context>().size)
        assertEquals(2, result.blocks.filterIsInstance<InstructionBlock.Step>().size)
    }

    @Test fun `running message is shown only for running and never changes the activity`() {
        val activity = SuggestedActivityUiModel("original-id", "🎵", "Una actividad", "1. Leé.\n2. Jugá.")
        val states = listOf(ActivityState.Loading, ActivityState.PreferencesRequired, ActivityState.NoEligibleActivities,
            ActivityState.Error("Error"), ActivityState.Suggested(activity))
        states.forEach { assertNull(activityRunningMessage(it)) }
        val running = ActivityState.Running(activity)
        assertEquals("Ahora podés dejar el celular a un lado. Si lo usás para poner música, dejalo apoyado. Podés volver a leer los pasos cuando quieras", activityRunningMessage(running))
        repeat(5) { parse(running.activity.description) }
        assertEquals(activity, running.activity)
        assertEquals("original-id", running.activity.activityId)
    }

    @Test fun `step navigation advances retreats clamps at boundaries and reports progress`() {
        val initial = ActivityStepNavigation("activity-a", stepCount = 4)
        assertEquals("Paso 1 de 4", initial.progressLabel)
        assertFalse(initial.canGoBack)
        assertEquals(initial, initial.previous())

        val third = initial.next().next()
        assertEquals(2, third.currentStepIndex)
        assertEquals("Paso 3 de 4", third.progressLabel)
        assertTrue(third.canGoBack)
        assertTrue(third.canGoForward)
        assertEquals(1, third.previous().currentStepIndex)

        val last = third.next()
        assertEquals("Paso 4 de 4", last.progressLabel)
        assertFalse(last.canGoForward)
        assertEquals(last, last.next())
    }

    @Test fun `changing activity resets visual progress without touching activity content`() {
        val current = ActivityStepNavigation("activity-a", 4).next().next()
        assertEquals(current, current.resetFor("activity-a", 4))

        val replacement = current.resetFor("activity-b", 3)
        assertEquals("activity-b", replacement.activityId)
        assertEquals(3, replacement.stepCount)
        assertEquals(0, replacement.currentStepIndex)
        assertEquals("Paso 1 de 3", replacement.progressLabel)
    }

    @Test fun `professional catalog maps all sixteen activity ids to specific visuals`() {
        val expected = mapOf(
            "movement_dance_01" to "🎵",
            "movement_sequence_01" to "👋",
            "movement_ball_01" to "⚽",
            "creative_character_story_01" to "🎭",
            "creative_animal_01" to "🐾",
            "creative_favorite_01" to "🖍️",
            "reading_choose_01" to "📖",
            "reading_favorite_01" to "📕",
            "free_play_nearby_01" to "🧸",
            "free_play_build_01" to "🧱",
            "free_play_story_01" to "🪆",
            "social_play_01" to "🧑‍🤝‍🧑",
            "social_conversation_story_01" to "💬",
            "social_create_together_01" to "🤝",
            "quiet_story_ending_01" to "🔀",
            "quiet_draw_01" to "✏️",
        )
        assertEquals(16, professionalActivityEmojiById.size)
        assertEquals(expected, professionalActivityEmojiById)
        expected.forEach { (activityId, emoji) -> assertEquals(emoji, activityEmoji(activityId)) }
    }

    @Test fun `unknown and retired activities use neutral visual instead of category inference`() {
        for (activityId in listOf("future_activity_01", "social_help_01", "quiet_prepare_01", "")) {
            assertEquals("✨", activityEmoji(activityId))
        }
    }
}
