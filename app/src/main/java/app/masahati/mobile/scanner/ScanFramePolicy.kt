package app.masahati.mobile.scanner

/** A second real JPEG is optional, not a saved low-resolution preview frame.
 * Good/slow/manual captures keep the fast path. HDR and sharpness bursts are
 * mutually exclusive at capture time to limit latency, storage and RAM.
 */
object ScanFramePolicy {
    fun shouldAttempt(zsl: Boolean,stable: Boolean,quality: CaptureQuality?,ageMs: Long,firstCaptureMs: Long)=
        zsl && stable && ageMs in 0..350 && firstCaptureMs in 1..450 && quality!=null &&
            quality.acceptable() && quality.sharpness<140

    fun improves(before: CaptureQuality,after: CaptureQuality)=
        after.sharpness>=before.sharpness*1.18 && after.darkFraction<=before.darkFraction+.01 &&
            after.clippedFraction<=before.clippedFraction+.003 && after.glareFraction<=before.glareFraction+.003
}
