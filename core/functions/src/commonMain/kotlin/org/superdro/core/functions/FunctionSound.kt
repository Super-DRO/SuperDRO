package org.superdro.core.functions

/**
 * A UI sound that a machine function asks for. `UiSounds` (a platform contract) plays it.
 */
enum class FunctionSound {
    /** 440 Hz, 0.1 s: a probe sample before the last; Z auto success. */
    Short,

    /** 440 Hz, 0.5 s: the last probe sample (edge found). */
    Long,

    /** Two 220 Hz beeps: a sample refused; Z auto failure. */
    Error,
}
