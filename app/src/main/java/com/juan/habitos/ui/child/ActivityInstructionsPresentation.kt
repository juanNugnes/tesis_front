package com.juan.habitos.ui.child

import com.juan.habitos.ui.state.ActivityState

/** Source slices remain lossless; this model is presentation-only, never used for selections or DTOs. */
internal data class ActivityInstructionsPresentation(val original: String, val blocks: List<InstructionBlock>) {
    val structured: Boolean get() = blocks.any { it !is InstructionBlock.Prose }
}

internal sealed interface InstructionBlock {
    val source: String
    data class Prose(override val source: String) : InstructionBlock
    data class Context(val heading: String, val text: String, override val source: String) : InstructionBlock {
        val displayText: String get() = if (heading.equals("Materiales", ignoreCase = true) && noMaterials.matches(text.trim()))
            "No necesitás materiales" else text
    }
    data class Heading(val text: String, override val source: String) : InstructionBlock
    data class Step(val number: String, val text: String, override val source: String) : InstructionBlock
}

private val noMaterials = Regex("(?:No necesit[aá]s materiales|No se necesitan materiales|No se requieren materiales|No requiere materiales|Ningunos?|Ningún material)[.!]?", RegexOption.IGNORE_CASE)
private val sectionHeader = Regex("^[ \\t]{0,3}(Materiales|Preparaci[oó]n|Antes de empezar|Participantes|Paso a paso|Pasos|Consignas):[ \\t]*(.*)$", RegexOption.IGNORE_CASE)
private val numberedStep = Regex("^[ \\t]{0,3}(?:([1-9][0-9]{0,2})[.)][ \\t]+|Paso[ \\t]+([1-9][0-9]{0,2}):[ \\t]*)", RegexOption.IGNORE_CASE)
private val sourceLines = Regex("[^\\r\\n]*(?:\\r\\n|\\n|\\r|$)")

internal fun parseActivityInstructions(description: String): ActivityInstructionsPresentation {
    fun plain() = ActivityInstructionsPresentation(description, listOf(InstructionBlock.Prose(description)))
    val lines = sourceLines.findAll(description).filter { it.value.isNotEmpty() }.toList()
    val headers = lines.map { sectionHeader.matchEntire(it.value.trimEnd('\r', '\n')) }
    val steps = lines.map { numberedStep.find(it.value) }
    val blocks = mutableListOf<InstructionBlock>()
    var index = 0
    var cursor = 0
    var expectedStep = 1

    fun proseUntil(end: Int) {
        if (end > cursor) blocks += InstructionBlock.Prose(description.substring(cursor, end))
        cursor = end
    }
    while (index < lines.size) {
        val line = lines[index]
        val header = headers[index]
        if (header != null) {
            val heading = header.groupValues[1]
            val instructionHeading = heading.equals("Pasos", true) || heading.equals("Consignas", true) || heading.equals("Paso a paso", true)
            if (expectedStep > 1) return plain() // A new section inside steps has ambiguous ownership.
            proseUntil(line.range.first)
            if (instructionHeading) {
                // Inline instructions after a heading are not silently discarded or made into a step.
                if (header.groupValues[2].isNotBlank()) return plain()
                blocks += InstructionBlock.Heading(heading, line.value)
                cursor = line.range.last + 1
                index++
                continue
            }
            var endIndex = (index + 1 until lines.size).firstOrNull { headers[it] != null } ?: lines.size
            if (endIndex < lines.size) {
                val nextHeading = headers[endIndex]!!.groupValues[1]
                val nextIsInstructions = nextHeading.equals("Pasos", true) || nextHeading.equals("Consignas", true) || nextHeading.equals("Paso a paso", true)
                if (!nextIsInstructions && (index + 1 until endIndex).any { steps[it] != null }) return plain()
            }
            // Without an explicit next heading, split only at a blank-line-separated numbered list.
            // A numbered list immediately under Materiales/Participantes may be that section's list.
            if (endIndex == lines.size) {
                val firstNumber = (index + 1 until endIndex).firstOrNull { steps[it] != null }
                if (firstNumber != null) {
                    val beforeNumber = description.substring(line.range.first + header.groups[2]!!.range.first, lines[firstNumber].range.first)
                    if (firstNumber == index + 1 || lines[firstNumber - 1].value.isNotBlank() || beforeNumber.isBlank()) return plain()
                    endIndex = firstNumber
                }
            }
            val end = lines.getOrNull(endIndex)?.range?.first ?: description.length
            val bodyStart = line.range.first + header.groups[2]!!.range.first
            val body = description.substring(bodyStart, end)
            if (body.isBlank()) return plain()
            blocks += InstructionBlock.Context(heading, body, description.substring(line.range.first, end))
            cursor = end
            index = endIndex
            continue
        }
        val step = steps[index]
        if (step != null) {
            val number = step.groupValues[1].ifEmpty { step.groupValues[2] }
            if (number.toInt() != expectedStep) return plain() // No renumbering, guessing or filling gaps.
            proseUntil(line.range.first)
            val endIndex = (index + 1 until lines.size).firstOrNull { headers[it] != null || steps[it] != null } ?: lines.size
            val end = lines.getOrNull(endIndex)?.range?.first ?: description.length
            val body = description.substring(line.range.first + step.value.length, end)
            if (body.isBlank()) return plain()
            blocks += InstructionBlock.Step(number, body, description.substring(line.range.first, end))
            cursor = end
            expectedStep++
            index = endIndex
            continue
        }
        index++
    }
    proseUntil(description.length)
    if (expectedStep == 1 && blocks.any { it is InstructionBlock.Heading }) return plain()
    return ActivityInstructionsPresentation(description, blocks).takeIf { it.structured } ?: plain()
}

internal fun activityRunningMessage(state: ActivityState): String? = if (state is ActivityState.Running)
    "Ahora podés dejar el celular a un lado. Si lo usás para poner música, dejalo apoyado. Podés volver a leer los pasos cuando quieras"
else null
