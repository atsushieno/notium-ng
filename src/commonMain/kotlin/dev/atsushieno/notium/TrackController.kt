package dev.atsushieno.notium

abstract class ControllerProcessingContext {
    abstract val primitiveProcessor: PrimitiveProcessor
}

class SimpleControllerProcessingContext : ControllerProcessingContext {
    constructor (primitiveProcessor: PrimitiveProcessor)

    private val processor: PrimitiveProcessor = primitiveProcessor

    override val primitiveProcessor: PrimitiveProcessor
        get() = processor
}

class Length(val value: Int) // value type?
{
    companion object {
        var baseCount: Int = 192
    }
}

fun Int.toLength() = Length(if (this == 0) 0 else Length.baseCount / this)

fun Length.toInt() = if (this.value == 0) 0 else Length.baseCount / this.value

abstract class PrimitiveProcessor {
    abstract fun debug(o: Any)

    abstract fun midiEvent(channel: Int, statusCode: Byte, data: Byte)

    abstract fun midiEvent(channel: Int, statusCode: Byte, data1: Byte, data2: Byte)
    abstract fun midiSysex(bytes: ByteArray, offset: Int, length: Int)
    abstract fun midiMeta(metaType: Int, vararg bytes: Byte)

    abstract fun midiMeta(metaType: Int, data: String)

    abstract fun beginLoop(channel: Int)

    abstract fun breakLoop(channel: Int, vararg targets: Int)

    abstract fun endLoop(channel: Int, repeats: Int)
}

class TrackController(private val context: ControllerProcessingContext) {

    private val primitive: PrimitiveProcessor
        get() = context.primitiveProcessor

    // Primitive length operators

    var timelinePosition: Int = 0

    fun step(length: Length) {
        timelinePosition += length.toInt()
    }

    fun jumpTo(length: Length) {
        timelinePosition = length.toInt()
    }

    fun rewind(length: Length) {
        timelinePosition -= length.toInt()
    }

    // end Primitive length operators

    var channel: Int = 0

    @Macro("CH")
    fun setChannelByNaturalNumber(channelByNaturalNumber: Byte) {
        channel = (channelByNaturalNumber - 1)
    }

    @Macro("DEBUG")
    fun debug(value: Any) = primitive.debug(value)

    @Macro("ASSERT_STEP")
    fun assetStep(expected: Length, label: String) {
        if (expected.toInt() != timelinePosition)
            primitive.debug("WARNING: step assertion failed: $label (expected: $expected, actual: ${timelinePosition.toInt()})")
    }

    // MIDI operators

    fun midiNoteOff(key: Byte, velocity: Byte) {
        primitive.midiEvent(channel, 0x80.toByte(), key, velocity)
    }

    fun midiNoteOn(key: Byte, velocity: Byte) {
        primitive.midiEvent(channel, 0x90.toByte(), key, velocity)
    }

    fun midiPAf(key: Byte, velocity: Byte) {
        primitive.midiEvent(channel, 0xA0.toByte(), key, velocity)
    }

    fun midiCC(opcode: Byte, operand: Byte) {
        primitive.midiEvent(channel, 0xB0.toByte(), opcode, operand)
    }

    fun midiProgramChange(program: Byte) {
        primitive.midiEvent(channel, 0xC0.toByte(), program, 0)
    }

    fun midiCAf(velocity: Byte) {
        primitive.midiEvent(channel, 0xD0.toByte(), velocity, 0)
    }

    fun midiPitch(value: Int) {
        primitive.midiEvent(channel, 0xE0.toByte(), (value % 0x80).toByte(), (value / 0x80).toByte())
    }

    fun midiMeta(metaType: Int, vararg bytes: Byte) {
        primitive.midiMeta(metaType, *bytes)
    }

    fun midiSysex(bytes: ByteArray, offset: Int, length: Int) {
        primitive.midiSysex(bytes, offset, length)
    }

    fun midiSysex(vararg bytes: Byte) {
        primitive.midiSysex(bytes, 0, bytes.size)
    }

    fun midiMeta(metaType: Int, data: String) {
        primitive.midiMeta(metaType, data)
    }

    // end MIDI operators

    @Macro("@")
    fun programWithBank(program: Byte, bankMsb: Byte, bankLsb: Byte) {
        midiCC(0, bankMsb)
        midiCC(0x20, bankLsb)
        midiProgramChange(program)
    }

    // Spectral changes

    class Spectra(private val controller: TrackController, onSetValueFunc: (Int) -> Unit) {

        private val onSetValue: (Int) -> Unit = onSetValueFunc

        private var preserved: Int = 0

        var value: Int
            get() = preserved
            set(value) {
                this.preserved = value
                onSetValue(value)
            }

        @MacroSuffix("_")
        fun oneShot(startValue: Int, endValue: Int, startDelay: Length, length: Length, deltaLength: Int = 4) {
            val initialTimelinePosition = controller.timelinePosition
            val workRepeatTime = length.toInt() / deltaLength
            controller.timelinePosition += startDelay.toInt()
            value = startValue
            for (i in 0 until workRepeatTime) {
                controller.timelinePosition += deltaLength
                value += (endValue - startValue) / (i + 1)
            }
            value = endValue
            controller.timelinePosition += initialTimelinePosition
        }

        @MacroSuffix("t")
        fun triangle(
            startValue: Int,
            endValue: Int,
            startDelay: Length,
            endDuration: Length,
            ts: Int,
            es: Int,
            delta: Int,
            repeats: Int
        ) {
            val initialTimelinePosition = controller.timelinePosition
            controller.timelinePosition += startDelay.toInt()
            value = startValue
            for (r in 0 until repeats) {
                for (i in 0 until ts / es) {
                    controller.timelinePosition += es
                    value += delta
                }
                for (i in 0 until ts / es) {
                    controller.timelinePosition += es
                    value -= delta
                }
            }
            controller.timelinePosition += endDuration.toInt()
            value = endValue
            controller.timelinePosition += initialTimelinePosition
        }
    }

    private var tempoPrimitive: Int = 120

    @Macro("TEMPO")
    var tempoValue: Int
        get() = tempoPrimitive
        set(value) {
            tempoPrimitive = value
            midiMeta(0x51, (value / 0x10000).toByte(), (value % 0x10000 / 0x100).toByte(), (value % 0x100).toByte())
        }

    @MacroRelative("t", "t+", "t-")
    val tempo: Spectra = Spectra(this) { v -> tempoValue = v }

    private var pitchbendCent: Int = 0

    @Macro("BEND_CENT_MODE")
    var pitchBendRatioByKeys: Int = 0

    @Macro("BEND")
    var pitchBendValue: Int
        get() = pitchbendCent
        set(value) {
            pitchbendCent = value
            midiPitch(if (pitchBendRatioByKeys != 0) value / 100 * 8192 / pitchBendRatioByKeys else value)
        }

    @Macro("PITCH_BEND_SENSITIVITY")
    fun pitchBendSensitivity(value: Byte) {
        dte(value, 0)
        rpn(0, 0)
    }

    @MacroRelative("B", "B+", "B-")
    val pitchBend: Spectra = Spectra(this) { v -> pitchBendValue = v }

    @MacroRelative("E", "E+", "E-")
    val expression: Spectra = Spectra(this) { v -> midiCC(0x0B, v.toByte()) }

    @MacroRelative("M", "M+", "M-")
    val modulation: Spectra = Spectra(this) { v -> midiCC(1, v.toByte()) }

    @MacroRelative("V", "V+", "V-")
    val volume: Spectra = Spectra(this) { v -> midiCC(7, v.toByte()) }

    @MacroRelative("P", "P+", "P-")
    val pan: Spectra = Spectra(this) { v -> midiCC(0x0A, v.toByte()) }

    @MacroRelative("H", "H+", "H-")
    val dumperPedal: Spectra = Spectra(this) { v -> midiCC(0x40, v.toByte()) }

    @Macro("DTEM")
    fun dteMsb(value: Byte) = midiCC(6, value)

    @Macro("DTEL")
    fun dteLsb(value: Byte) = midiCC(0x26, value)

    @Macro("DTE")
    fun dte(msb: Byte, lsb: Byte) {
        dteMsb(msb)
        dteLsb(lsb)
    }

    @Macro("SOS")
    fun sostenuto(value: Byte) = midiCC(0x42, value)

    @Macro("SOFT")
    fun softPedal(value: Byte) = midiCC(0x43, value)

    @Macro("LEGATO")
    fun legato(value: Byte) = midiCC(0x54, value)

    @MacroRelative("RSD", "RSD+", "RSD-")
    val reverbSendDepth: Spectra = Spectra(this) { v -> midiCC(0x5B, v.toByte()) }

    @MacroRelative("CSD", "CSD+", "CSD-")
    val chorusSendDepth: Spectra = Spectra(this) { v -> midiCC(0x5D, v.toByte()) }

    @MacroRelative("DSD", "DSD+", "DSD-")
    val delaySendDepth: Spectra = Spectra(this) { v -> midiCC(0x5E, v.toByte()) }

    @Macro("NRPNM")
    fun nrpnMSb(value: Byte) = midiCC(0x63, value)

    @Macro("NRPNL")
    fun nrpnLSb(value: Byte) = midiCC(0x62, value)

    @Macro("NRPN")
    fun nrpn(msb: Byte, lsb: Byte) {
        nrpnMSb(msb)
        nrpnLSb(lsb)
    }

    @Macro("RPNM")
    fun rpnMSb(value: Byte) = midiCC(0x65, value)

    @Macro("RPNL")
    fun rpnLSb(value: Byte) = midiCC(0x64, value)

    @Macro("RPN")
    fun rpn(msb: Byte, lsb: Byte) {
        rpnMSb(msb)
        rpnLSb(lsb)
    }

    // end Control changes

    // META events

    fun text(value: String) = midiMeta(1, value)

    fun copyright(value: String) = midiMeta(2, value)

    fun trackName(value: String) = midiMeta(3, value)

    fun instrumentName(value: String) = midiMeta(4, value)

    fun lyric(value: String) = midiMeta(5, value)

    fun marker(value: String) = midiMeta(6, value)

    fun cue(value: String) = midiMeta(7, value)

    fun beat(value: Byte, denominator: Int) {
        midiMeta(
            0x58, value, when {
                denominator == 2 -> 1
                denominator == 4 -> 2
                denominator == 8 -> 3
                denominator == 16 -> 4
                else -> denominator
            }.toByte(),
            0, 0
        )
    }

    // end META events

    // Note flavors

    @Macro("v")
    var velocity: Int = 100

    var velocityRelativeSensitivity: Int = 4

    @Macro(")")
    fun increateVelocity() {
        velocity += velocityRelativeSensitivity
    }

    @Macro("(")
    fun decreateVelocity() {
        velocity -= velocityRelativeSensitivity
    }

    @Macro("l")
    var defaultLength: Length = 4.toLength()

    @Macro("TIMING")
    var keyDelay: Int = 0

    @Macro("GATE_DENOM")
    var gateTimeDenominator: Int = 8

    @Macro("Q")
    var gateTimeRelative: Int = 8

    @Macro("q")
    var gateTimeAbsolute: Int = 0

    @Macro("o")
    var octave: Int = 4

    @Macro(">")
    fun increaseOctave() {
        octave++
    }

    @Macro("<")
    fun decreaseOctave() {
        octave--
    }

    @Macro("K")
    var transpose: Int = 0
    var transposeC: Int = 0
    var transposeD: Int = 0
    var transposeE: Int = 0
    var transposeF: Int = 0
    var transposeG: Int = 0
    var transposeA: Int = 0
    var transposeB: Int = 0

    @Macro("Kc+")
    fun transposeCSharp() {
        transposeC = 1
    }

    @Macro("Kd+")
    fun transposeDSharp() {
        transposeD = 1
    }

    @Macro("Ke+")
    fun transposeESharp() {
        transposeE = 1
    }

    @Macro("Kf+")
    fun transposeFSharp() {
        transposeF = 1
    }

    @Macro("Kg+")
    fun transposeGSharp() {
        transposeG = 1
    }

    @Macro("Ka+")
    fun transposeASharp() {
        transposeA = 1
    }

    @Macro("Kb+")
    fun transposeBSharp() {
        transposeB = 1
    }

    @Macro("Kc-")
    fun transposeCFlat() {
        transposeC = -1
    }

    @Macro("Kd-")
    fun transposeDFlat() {
        transposeD = -1
    }

    @Macro("Ke-")
    fun transposeEFlat() {
        transposeE = -1
    }

    @Macro("Kf-")
    fun transposeFFlat() {
        transposeF = -1
    }

    @Macro("Kg-")
    fun transposeGFlat() {
        transposeG = -1
    }

    @Macro("Ka-")
    fun transposeAFlat() {
        transposeA = -1
    }

    @Macro("Kb-")
    fun transposeBFlat() {
        transposeB = -1
    }

    @Macro("Kc=")
    fun transposeCNatural() {
        transposeC = 0
    }

    @Macro("Kd=")
    fun transposeDNatural() {
        transposeD = 0
    }

    @Macro("Ke=")
    fun transposeENatural() {
        transposeE = 0
    }

    @Macro("Kf=")
    fun transposeFNatural() {
        transposeF = 0
    }

    @Macro("Kg=")
    fun transposeGNatural() {
        transposeG = 0
    }

    @Macro("Ka=")
    fun transposeANatural() {
        transposeA = 0
    }

    @Macro("Kb=")
    fun transposeBNatural() {
        transposeB = 0
    }

    // end Note flavors

    // Note and rest operators

    @Macro("n")
    fun note(
        key: Byte,
        step: Int = -1,
        gate: Int = -1,
        velocity: Int = -1,
        keyDelay: Int = -1,
        noteOffVelocity: Byte = 0
    ) {
        val actualStep = if (step < 0) defaultLength.toInt() else step
        this.velocity = if (velocity < 0) this.velocity else velocity
        this.keyDelay = if (keyDelay < 0) this.keyDelay else keyDelay

        val currentNoteStep = if (gate < 0) actualStep else gate
        val currentNoteGate =
            (currentNoteStep * gateTimeRelative * (1.0 / gateTimeDenominator)).toInt() - gateTimeAbsolute

        step(this.keyDelay.toLength())
        midiNoteOn(key, velocity.toByte())
        step(currentNoteGate.toLength())
        // see SyncNoteOffWithNext()
        // OnMidiNoteOff (currentNoteGate, key, velocity);
        midiNoteOff(key, noteOffVelocity)
        step((actualStep - currentNoteGate).toLength())
        rewind(this.keyDelay.toLength())
    }

    /*
    I'm going to remove support for "&" arpeggio support
    because it does not cope with "live" operations.
    Arpeggio can be achieved by some starter marking, not suffixed operator e.g. `ARPceg2.ARPfa>c2.`

    "&" was introduced in mugene because its referent syntax
    MUC had the operator and it was quite useful.
    But it was a language from 20C which never cared live
    operations...

    This will simplify MML processing significantly.

    [Macro ("&")]
    public void SyncNoteOffWithNext ()
    {
        primitive.SyncNoteOffWithNext (channel);
    }
    */

    class NoteGroup(controller: TrackController, baseKey: Byte, transposeSpecific: () -> Int) {

        @MacroSuffix("")
        val base: NoteOperator = NoteOperator(controller, baseKey, transposeSpecific)

        @MacroSuffix("-")
        val flat: NoteOperator = NoteOperator(controller, baseKey) { -1 }

        @MacroSuffix("=")
        val natural: NoteOperator = NoteOperator(controller, baseKey) { 0 }

        @MacroSuffix("+")
        val sharp: NoteOperator = NoteOperator(controller, baseKey) { 1 }
    }

    class NoteOperator(private val controller: TrackController, val baseKey: Byte, var transposeSpecific: () -> Int) {

        fun note(step: Int = -1, gate: Int = -1, velocity: Int = -1, keyDelay: Int = -1, noteOffVelocity: Byte = 0) =
            controller.note(
                (controller.octave * 12 + baseKey + transposeSpecific() + controller.transpose).toByte(),
                step,
                gate,
                velocity,
                keyDelay,
                noteOffVelocity
            )
    }

    @MacroNote("c")
    val noteC: NoteGroup = NoteGroup(this, 0) { transposeC }

    @MacroNote("d")
    val noteD: NoteGroup = NoteGroup(this, 2) { transposeD }

    @MacroNote("e")
    val noteE: NoteGroup = NoteGroup(this, 4) { transposeE }

    @MacroNote("f")
    val noteF: NoteGroup = NoteGroup(this, 5) { transposeF }

    @MacroNote("g")
    val noteG: NoteGroup = NoteGroup(this, 7) { transposeG }

    @MacroNote("a")
    val noteA: NoteGroup = NoteGroup(this, 9) { transposeA }

    @MacroNote("b")
    val noteB: NoteGroup = NoteGroup(this, 11) { transposeB }

    @Macro("r")
    fun rest(step: Int) = step(step.toLength())

    // end Note and rest operators

    // Loop operators

    // I'm not sure if defining loop in API makes sense, but so far for backward compatibility...

    @Macro("[")
    fun beginLoop() = primitive.beginLoop(channel)

    // WTF Kotlin!? "Repeatable annotations with non-SOURCE retention are not yet supported"
    @Macro(":")
    //@Macro("/")
    fun breakLoop(vararg targets: Int) = primitive.breakLoop(channel, *targets)

    @Macro("]")
    fun endLoop(repeats: Int) = primitive.endLoop(channel, repeats)

    // end Loop operators

    @Macro("GM_SYSTEM_ON")
    fun gmSystemOn() = midiSysex(0xF0.toByte(), 0x7E, 0x7F, 0x09, 0x01, 0xF7.toByte())

    @Macro("XG_RESET")
    fun xgReset() = midiSysex(0xF0.toByte(), 0x43, 0x10, 0x4C, 0, 0, 0x7E, 0, 0xF7.toByte())

}
