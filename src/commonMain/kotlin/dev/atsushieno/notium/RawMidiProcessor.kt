@file:Suppress("unused")

package dev.atsushieno.notium

import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.MidiAccessManager
import dev.atsushieno.ktmidi.MidiOutput


class RawMidiProcessor : PrimitiveProcessor {
    constructor () : this (MidiAccessManager.empty)

    constructor ( access: MidiAccess) : this (access.openOutputAsync (access.outputs.first ().id))

    constructor ( midiOutput: MidiOutput) {
        output = midiOutput
    }

    private val output: MidiOutput

    override fun beginLoop (channel: Int) {
        throw UnsupportedOperationException ()
     }

    override fun breakLoop ( channel: Int, vararg  targets: Int) {
        throw  UnsupportedOperationException ()
    }

    override fun debug (o: Any) {
        throw UnsupportedOperationException ()
    }

    override fun endLoop ( channel: Int, repeats: Int) {
        throw UnsupportedOperationException ()
    }

    private val buffer = ByteArray(128)

     override fun midiEvent ( channel: Int, statusCode: Byte, data: Byte) {
        buffer [0] = (statusCode + channel).toByte()
         buffer [1] = data
         output.send (buffer, 0, 2, 0)
     }

    override fun midiEvent ( channel: Int, statusCode: Byte, data1 : Byte, data2: Byte) {
        buffer [0] = (statusCode + channel).toByte()
        buffer [1] = data1
        buffer [2] = data2
        output.send (buffer, 0, 3, 0)
    }

     override fun midiMeta ( metaType: Int, vararg bytes: Byte) {
        // FIXME: implement
    }

     override fun midiMeta ( metaType: Int, data: String) {
         // FIXME: implement
    }

     override fun midiSysex (bytes: ByteArray, offset: Int, length: Int) {
        output.send (bytes, offset, length, 0)
     }
}
