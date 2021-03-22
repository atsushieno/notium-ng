@file:Suppress("unused")

package dev.atsushieno.notium

class PrimitiveMmlProcessingContext : ControllerProcessingContext {
    private val primitive : PrimitiveProcessor

    constructor (output: (String) -> Unit, errorOutput: ((String) -> Unit)?)
        : this (PrimitiveMmlProcessor (output, errorOutput))

    constructor ( primitive: PrimitiveMmlProcessor) {
        this.primitive = primitive
    }

    override val primitiveProcessor: PrimitiveProcessor
        get() = primitive
}

class PrimitiveMmlProcessor(private val output: (String) -> Unit, debugOutput: ((String) -> Unit)? = null) :
    PrimitiveProcessor() {

    private val debugOutput: ((String) -> Unit)?

    override fun beginLoop(channel: Int) {
        output("[")
    }

    override fun breakLoop(channel: Int, vararg targets: Int) {
        output(":" + targets.joinToString(",") { t -> t.toString() })
    }

    override fun debug(o: Any) {
        (debugOutput ?: output) (o.toString() + "\n")
    }

    override fun endLoop(channel: Int, repeats: Int) {
        output("]$repeats")
    }

    override fun midiEvent(channel: Int, statusCode: Byte, data: Byte) {
        output("__MIDI { $statusCode, $data }")
    }

    override fun midiEvent(channel: Int, statusCode: Byte, data1: Byte, data2: Byte) {
        output("__MIDI { $statusCode, $data1, $data2 } ")
    }

    override fun midiMeta(metaType: Int, vararg bytes: Byte) {
        output("__MIDI_META { $metaType")
        for (b in bytes) {
            output(", $b")
        }
        output("} ")
    }

    override fun midiMeta(metaType: Int, data: String) {
        val escaped = data.replace("\\", "\\\\").replace("\"", "\\\"")
        output("__MIDI_META { $metaType, \"$escaped\" } ")
    }

    override fun midiSysex(bytes: ByteArray, offset: Int, length: Int) {
        output("__MIDI")
        output("#F0")
        for (b in bytes.drop(offset).take(length)) {
            output(", #{b:x02}")
        }
        output("} ")
    }

    init {
        this.debugOutput = debugOutput ?: { s -> println(s) }
    }
}

