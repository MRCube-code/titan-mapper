package com.titanmapper.core.model

/**
 * Structural validation shared by the macro editor, the importer and the executor.
 * Imported macros are untrusted input, so every limit here is enforced before execution.
 */
object MacroValidator {
    const val MAX_TOTAL_STEPS = 10_000
    const val MAX_DEPTH = 8
    const val MAX_DELAY_MILLIS = 3_600_000L // 1 hour
    const val MAX_LOOP_COUNT = 100_000
    const val MAX_NAME_LENGTH = 100

    fun validate(macro: MacroDefinition): List<String> {
        val errors = ArrayList<String>()
        if (macro.name.isBlank()) errors += "Macro name is empty"
        if (macro.name.length > MAX_NAME_LENGTH) errors += "Macro name is longer than $MAX_NAME_LENGTH characters"
        val counter = IntArray(1)
        checkSteps(macro.steps, 0, counter, errors)
        if (counter[0] > MAX_TOTAL_STEPS) errors += "Macro has more than $MAX_TOTAL_STEPS steps"
        return errors
    }

    /** Counts every step including nested conditional branches. */
    fun countSteps(steps: List<MacroStep>): Int =
        steps.sumOf { s ->
            1 + if (s is MacroStep.ConditionalIf) countSteps(s.thenSteps) + countSteps(s.elseSteps) else 0
        }

    private fun checkSteps(steps: List<MacroStep>, depth: Int, counter: IntArray, errors: MutableList<String>) {
        if (depth > MAX_DEPTH) {
            errors += "Conditionals nested deeper than $MAX_DEPTH levels"
            return
        }
        var open = 0
        for (s in steps) {
            counter[0]++
            when (s) {
                is MacroStep.Delay ->
                    if (s.millis < 0 || s.millis > MAX_DELAY_MILLIS) errors += "Delay ${s.millis} ms is out of range"
                is MacroStep.LoopStart -> {
                    open++
                    val c = s.count
                    if (c != null && (c < 1 || c > MAX_LOOP_COUNT)) errors += "Loop count $c is out of range"
                    if (open > MAX_DEPTH) errors += "Loops nested deeper than $MAX_DEPTH levels"
                }
                MacroStep.LoopEnd -> {
                    open--
                    if (open < 0) {
                        errors += "Loop end without a matching loop start"
                        open = 0
                    }
                }
                is MacroStep.ConditionalIf -> {
                    checkSteps(s.thenSteps, depth + 1, counter, errors)
                    checkSteps(s.elseSteps, depth + 1, counter, errors)
                }
                else -> Unit
            }
        }
        if (open > 0) errors += "Loop start without a matching loop end"
    }
}