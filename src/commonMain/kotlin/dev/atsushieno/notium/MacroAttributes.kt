package dev.atsushieno.notium

annotation class Macro(val name: String)

annotation class MacroSuffix(val suffix: String)

annotation class MacroNote(val name: String)

annotation class MacroRelative(val name: String, val increase: String, val decrease: String)
