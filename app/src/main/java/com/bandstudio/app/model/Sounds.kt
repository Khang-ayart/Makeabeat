package com.bandstudio.app.model

import kotlin.math.pow
import kotlin.math.roundToInt

/** Các hàng của Looper, theo thứ tự từ trên xuống. */
enum class SoundCat(val label: String, val color: Long) {
    KICK("Kick", 0xFFE5534B),
    SNARE("Snare", 0xFFF2A444),
    HAT("Hi-hat", 0xFF2FBF8F),
    PERC("Percussion", 0xFF5B8CFF),
    BASS("808 / Bass", 0xFF9B6BFF),
    KEYS("Keyboard", 0xFFE0568F),  // vòng hợp âm: không có trong kit, nốt nằm ngay trong nhịp của pad
    LEAD("Synth", 0xFF8A6BFF),     // synth lead / arp: nốt của hợp âm, phát bằng giọng synth trong engine
    ATMOS("Pad", 0xFF3FC1D9);      // synth pad / atmos: hợp âm nền kéo dài

    /** Hàng có âm riêng trong kit (kick..bass). Hàng giai điệu (Keyboard, Synth, Pad) phát nốt trực tiếp. */
    val hasKit: Boolean get() = ordinal < KIT_ROWS
}

/** Số hàng có kit (Kick, Snare, Hi-hat, Percussion, Bass); các hàng sau là hàng giai điệu. */
const val KIT_ROWS = 5

/** Mỗi hàng có 4 pad (4 lựa chọn). Pad số i của track Looper = hàng i / 4, cột i % 4. */
const val PADS_PER_ROW = 4
val PAD_COUNT = SoundCat.entries.size * PADS_PER_ROW                 // 32 pad (8 hàng x 4)
/** Mask pad từ engine: bit 0..31 = pad đang chạy, bit 32..63 = pad đang chờ đổi trạng thái ở đầu ô nhịp kế. */
const val PENDING_SHIFT = 32
val KIT_COUNT = KIT_ROWS * PADS_PER_ROW                              // 20 âm trong kit (hàng giai điệu không cần âm riêng)

/**
 * Thông số một âm one-shot, khớp struct Patch trong song.h. Engine ghép: tone (sin trượt cao độ)
 * + nhiễu (lọc cao/thấp) + tone thứ hai + méo mềm. Mọi âm trong danh mục chỉ là một bộ số.
 */
data class Patch(
    val toneAmp: Float = 0f,
    val fStart: Float = 100f,
    val fEnd: Float = 100f,
    val pitchTauMs: Float = 30f,
    val toneTauMs: Float = 200f,
    val noiseAmp: Float = 0f,
    val noiseTauMs: Float = 100f,
    val noiseHP: Float = 0f,
    val noiseLP: Float = 0f,
    val bursts: Int = 0,
    val burstGapMs: Float = 11f,
    val tailTauMs: Float = 90f,
    val tone2Ratio: Float = 0f,
    val tone2Amp: Float = 0f,
    val chokeGroup: Int = 0,
    val gain: Float = 1f,
    val drive: Float = 0f,
    val send: Float = 0f,           // 0..1: gửi vào reverb chung
    val root: Float = 0f,           // > 0 (Hz): mẫu có cao độ gốc, phát theo fStart->fEnd
    val vary: Float = 0f,           // 0..1: lệch nhẹ cao độ/độ to mỗi cú đánh
    val sample: Int = -1,           // >= 0: ô mẫu âm thật trong engine (nếu nạp được); không thì dùng phần tổng hợp
) {
    /** Thứ tự phải khớp decodeSong: số thực nhân 1000; bursts và chokeGroup là số nguyên thô. */
    fun encodeTo(out: MutableList<Int>) {
        fun q(v: Float) = (v * 1000f).roundToInt()
        out.add(q(toneAmp)); out.add(q(fStart)); out.add(q(fEnd)); out.add(q(pitchTauMs)); out.add(q(toneTauMs))
        out.add(q(noiseAmp)); out.add(q(noiseTauMs)); out.add(q(noiseHP)); out.add(q(noiseLP))
        out.add(bursts); out.add(q(burstGapMs)); out.add(q(tailTauMs)); out.add(q(tone2Ratio)); out.add(q(tone2Amp))
        out.add(chokeGroup); out.add(q(gain)); out.add(q(drive)); out.add(sample); out.add(q(send)); out.add(q(root)); out.add(q(vary))
    }

    fun transposed(semitones: Int): Patch {
        if (semitones == 0) return this
        val k = 2f.pow(semitones / 12f)
        return copy(fStart = fStart * k, fEnd = fEnd * k)
    }

    companion object {
        const val INTS = 21  // khớp kPatchInts
        val SILENT = Patch()
    }
}

class Sound(val id: String, val label: String, val cat: SoundCat, val patch: Patch)

/** Một pad trong kit của track: âm nào, và (với hàng Bass) cao hơn âm gốc E1 bao nhiêu nửa cung. */
data class PadSpec(val soundId: String, val semitones: Int = 0)

class Genre(
    val id: String,
    val label: String,
    val bpm: Int,
    val family: String,                // họ nhịp trong Patterns
    private val picks: Map<SoundCat, List<String>>,
    private val bassSound: String,
    val minor: Boolean,                  // thang thứ hay trưởng: quyết định hợp âm và quãng của bass
    private val bassIntervals: List<Int>,
) {
    /**
     * 20 âm theo thứ tự hàng KICK, SNARE, HAT, PERC, BASS (mỗi hàng 4). Bass nằm trong tông [keyRoot] (0 = C ... 11 = B),
     * gốc ở khoảng E1-D#2 (41-78 Hz) để loa điện thoại còn nghe được.
     */
    fun kit(keyRoot: Int = DEFAULT_KEY): List<PadSpec> {
        val root = Math.floorMod(keyRoot - 4, 12)  // nửa cung trên E1 (E = nốt số 4 của C = 0)
        return SoundCat.entries.filter { it.hasKit }.flatMap { cat ->
            if (cat == SoundCat.BASS) bassIntervals.map { PadSpec(bassSound, root + it) }
            else picks.getValue(cat).map { PadSpec(it) }
        }
    }
}

const val DEFAULT_KEY = 9  // A: giữ nguyên bass cũ (A1) cho các dự án có sẵn
val KEY_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

object Catalog {
    private const val E1 = 41.2f  // gốc của các âm bass; PadSpec.semitones dịch lên từ đây
    const val BASS_E1_MIDI = 28

    private fun s(id: String, label: String, cat: SoundCat, p: Patch) = Sound(id, label, cat, p)

    // Kick: tone trượt từ cao xuống thấp + một tiếng "click" nhiễu rất ngắn.
    private val kicks = listOf(
        s("kick.soft", "Mềm", SoundCat.KICK, Patch(0.8f, 110f, 55f, 35f, 170f, 0.08f, 3f, 1f, 0.3f)),
        s("kick.deep", "Trầm sâu", SoundCat.KICK, Patch(0.9f, 90f, 40f, 60f, 380f, 0.05f, 3f, 1f, 0.5f)),
        s("kick.punch", "Dứt khoát", SoundCat.KICK, Patch(0.9f, 180f, 55f, 22f, 150f, 0.3f, 5f, 1f)),
        s("kick.tight", "Gọn", SoundCat.KICK, Patch(0.9f, 150f, 60f, 15f, 90f, 0.2f, 3f, 1f)),
        s("kick.rock", "Rock", SoundCat.KICK, Patch(0.9f, 170f, 65f, 28f, 210f, 0.35f, 8f, 0.5f)),
        s("kick.boom", "808 Boom", SoundCat.KICK, Patch(0.95f, 130f, 38f, 70f, 600f, 0.05f, 3f, 1f, 0.4f, drive = 1.5f)),
        s("kick.lofi", "Lo-fi", SoundCat.KICK, Patch(0.85f, 100f, 50f, 30f, 140f, 0.15f, 12f, 0.2f, 0.8f, drive = 0.6f)),
        s("kick.edm", "EDM", SoundCat.KICK, Patch(0.9f, 200f, 48f, 18f, 260f, 0.2f, 2f, 1f)),
    )

    // Snare: tone thân + nhiễu "dây" (lọc cao) với độ vang khác nhau. Clap = nhiều tiếng vỗ nối nhau.
    private val snares = listOf(
        s("snare.soft", "Mềm", SoundCat.SNARE, Patch(0.35f, 190f, 170f, 20f, 60f, 0.4f, 110f, 0.6f, 0.3f)),
        s("snare.brush", "Tape khô", SoundCat.SNARE, Patch(0.1f, 180f, 170f, 20f, 40f, 0.45f, 130f, 0.5f, 0.55f)),
        s("snare.rim", "Gõ viền (rim)", SoundCat.SNARE,
            Patch(0.7f, 450f, 400f, 10f, 25f, 0.25f, 25f, 0.8f, tone2Ratio = 1.6f, tone2Amp = 0.5f)),
        s("snare.pop", "Pop", SoundCat.SNARE, Patch(0.5f, 200f, 180f, 20f, 80f, 0.6f, 120f, 0.7f)),
        s("snare.crack", "Giòn (rock)", SoundCat.SNARE, Patch(0.55f, 220f, 190f, 20f, 90f, 0.8f, 180f, 0.75f)),
        s("snare.trap", "Trap", SoundCat.SNARE, Patch(0.35f, 240f, 230f, 20f, 60f, 0.7f, 150f, 1f)),
        s("snare.clap", "Clap", SoundCat.SNARE, Patch(noiseAmp = 0.65f, noiseTauMs = 12f, noiseHP = 0.7f, bursts = 3, burstGapMs = 11f, tailTauMs = 90f)),
        s("snare.lofi", "Lo-fi", SoundCat.SNARE, Patch(0.4f, 180f, 160f, 20f, 70f, 0.45f, 140f, 0.4f, 0.7f, drive = 0.5f)),
    )

    // Hi-hat đóng và mở cùng nhóm chặn (chokeGroup 1): bấm hi-hat đóng sẽ ngắt hi-hat mở.
    private val hats = listOf(
        s("hat.soft", "Mềm", SoundCat.HAT, Patch(noiseAmp = 0.25f, noiseTauMs = 35f, noiseHP = 1f, noiseLP = 0.35f)),
        s("hat.tight", "Gọn", SoundCat.HAT, Patch(noiseAmp = 0.35f, noiseTauMs = 22f, noiseHP = 1f)),
        s("hat.closed", "Đóng", SoundCat.HAT, Patch(noiseAmp = 0.35f, noiseTauMs = 45f, noiseHP = 1f, chokeGroup = 1)),
        s("hat.open", "Mở", SoundCat.HAT, Patch(noiseAmp = 0.32f, noiseTauMs = 300f, noiseHP = 1f, chokeGroup = 1)),
        s("hat.trap", "Trap tick", SoundCat.HAT, Patch(noiseAmp = 0.3f, noiseTauMs = 14f, noiseHP = 1f)),
        s("hat.shaker", "Khô", SoundCat.HAT, Patch(noiseAmp = 0.3f, noiseTauMs = 70f, noiseHP = 0.7f, noiseLP = 0.5f)),
        s("hat.lofi", "Lo-fi", SoundCat.HAT, Patch(noiseAmp = 0.3f, noiseTauMs = 50f, noiseHP = 0.8f, noiseLP = 0.6f)),
    )

    private val percs = listOf(
        s("perc.shaker", "Shaker", SoundCat.PERC, Patch(noiseAmp = 0.3f, noiseTauMs = 55f, noiseHP = 0.6f, noiseLP = 0.5f)),
        s("perc.tamb", "Tambourine", SoundCat.PERC,
            Patch(noiseAmp = 0.35f, noiseTauMs = 80f, noiseHP = 1f, noiseLP = 0.2f, bursts = 2, burstGapMs = 9f, tailTauMs = 70f)),
        s("perc.snap", "Clap ngắn", SoundCat.PERC, Patch(0.3f, 1800f, 1200f, 10f, 20f, 0.5f, 25f, 1f, 0.2f)),
        s("perc.conga", "Conga", SoundCat.PERC, Patch(0.8f, 330f, 250f, 25f, 90f, 0.08f, 5f, 0.5f)),
        s("perc.bongo", "Bongo", SoundCat.PERC, Patch(0.8f, 480f, 380f, 20f, 60f, 0.08f, 5f, 0.5f)),
        s("perc.cowbell", "Cowbell", SoundCat.PERC, Patch(0.6f, 560f, 560f, 30f, 120f, tone2Ratio = 1.48f, tone2Amp = 0.8f)),
        s("perc.clave", "Clave", SoundCat.PERC, Patch(0.8f, 2500f, 2400f, 10f, 18f)),
        s("perc.tom", "Tom", SoundCat.PERC, Patch(0.85f, 170f, 100f, 60f, 220f, 0.08f, 10f, 0.5f)),
        s("perc.ride", "Ride", SoundCat.PERC, Patch(0.12f, 4200f, 4200f, 30f, 220f, 0.2f, 300f, 1f)),
        s("perc.crash", "Crash", SoundCat.PERC, Patch(noiseAmp = 0.3f, noiseTauMs = 500f, noiseHP = 1f)),
    )

    // Bass: định nghĩa ở nốt E1; hàng Bass của thể loại dịch lên bằng PadSpec.semitones.
    private val basses = listOf(
        s("bass.808long", "808 dài", SoundCat.BASS, Patch(1f, E1 * 1.6f, E1, 40f, 700f, drive = 0.3f, gain = 0.9f)),
        s("bass.808short", "808 ngắn", SoundCat.BASS, Patch(1f, E1 * 1.5f, E1, 25f, 250f, gain = 0.9f)),
        s("bass.808dist", "808 rè", SoundCat.BASS, Patch(1f, E1 * 1.6f, E1, 40f, 600f, drive = 4f, gain = 0.9f)),
        s("bass.finger", "Bass guitar", SoundCat.BASS, Patch(1f, E1, E1, 30f, 600f, gain = 0.9f)),
        s("bass.sub", "Sub mềm", SoundCat.BASS, Patch(1f, E1, E1, 30f, 450f, gain = 0.9f)),
        s("bass.pluck", "Bass gảy", SoundCat.BASS,
            Patch(0.9f, E1, E1, 30f, 300f, 0.1f, 4f, 0f, 0.5f, tone2Ratio = 2f, tone2Amp = 0.4f)),
        s("bass.synth", "Synth bass", SoundCat.BASS, Patch(0.8f, E1, E1, 30f, 350f, tone2Ratio = 2f, tone2Amp = 0.8f, drive = 1.2f)),
    )

    /**
     * Mẫu âm thật (CC0, xem assets/samples/SOURCES.md): mọi âm ngoài hàng Bass có tệp assets/samples/<id>.wav.
     * Thông số tổng hợp ở trên vẫn giữ làm dự phòng khi mẫu chưa nạp được. Ô mẫu = vị trí trong [sampleIds].
     */
    private val allSounds: List<Sound> = kicks + snares + hats + percs + basses
    /** 808 mẫu thật: tần số gốc (Hz) đo từ mẫu; engine đổi cao độ và trượt nốt theo fStart -> fEnd của patch. */
    private val sampledBassRoot = mapOf("bass.808long" to 55.2f, "bass.808short" to 49.1f, "bass.808dist" to 51.0f, "bass.finger" to 65.4f)
    val sampleIds: List<String> = allSounds.filter { it.cat != SoundCat.BASS || it.id in sampledBassRoot }.map { it.id }
    private val sampleGain = mapOf(SoundCat.KICK to 1f, SoundCat.SNARE to 1f, SoundCat.HAT to 1f, SoundCat.PERC to 1f, SoundCat.BASS to 0.9f) // độ to đã cân sẵn trong mẫu
    // Lệch nhẹ mỗi cú đánh (round robin giả): hi-hat/perc/snare nhiều hơn, kick ít, bass giữ nguyên cao độ.
    private val sampleVary = mapOf(SoundCat.KICK to 0.5f, SoundCat.SNARE to 0.8f, SoundCat.HAT to 1f, SoundCat.PERC to 1f, SoundCat.BASS to 0f)
    val sounds: List<Sound> = allSounds.map { snd ->
        val slot = sampleIds.indexOf(snd.id)
        if (slot < 0) snd else {
            val root = sampledBassRoot[snd.id] ?: 0f
            var p = snd.patch.copy(sample = slot, gain = sampleGain.getValue(snd.cat), noiseLP = 0f, root = root, vary = sampleVary.getValue(snd.cat))
            if (root > 0f) p = p.copy(fStart = E1, fEnd = E1)  // không glide sẵn: mẫu đã có đầu đánh riêng
            Sound(snd.id, snd.label, snd.cat, p)
        }
    }
    private val byId: Map<String, Sound> = sounds.associateBy { it.id }

    fun sound(id: String): Sound? = byId[id]

    /** Xử lý "chung màu" theo thể loại: độ tối (bộ lọc thấp 0..1), hệ số âm lượng, lượng gửi reverb. */
    data class Tone(val lowPass: Float, val gain: Float, val send: Float)

    /**
     * Mẫu thô của hi-hat, percussion sáng và to hơn kick/bass nên nghe tách rời; snare khô và to nhất bộ nên "lạc loài".
     * Xử lý: hạ tiếng + làm tối hi-hat/percussion, hạ nhẹ snare và cho snare gửi vào reverb chung để dính vào bộ.
     * Kick và bass giữ nguyên.
     */
    fun toneFor(genreId: String, cat: SoundCat): Tone {
        val soft = genreId == "rnb" || genreId == "ballad" || genreId == "jazz" || genreId == "acoustic" || genreId == "melodic"
        return when (cat) {
            SoundCat.HAT -> when {
                genreId == "lofi" -> Tone(0.50f, 0.55f, 0f)
                soft -> Tone(0.35f, 0.60f, 0f)
                genreId == "hiphop" -> Tone(0.40f, 0.62f, 0f)
                else -> Tone(0.15f, 0.70f, 0f)
            }
            SoundCat.PERC -> when {
                genreId == "lofi" -> Tone(0.45f, 0.55f, 0.15f)
                soft -> Tone(0.30f, 0.65f, 0.15f)
                genreId == "hiphop" -> Tone(0.35f, 0.68f, 0.12f)
                else -> Tone(0.10f, 0.75f, 0.10f)
            }
            SoundCat.SNARE -> when {
                genreId == "lofi" -> Tone(0.40f, 0.80f, 0.45f)
                soft -> Tone(0.25f, 0.85f, 0.40f)
                genreId == "hiphop" -> Tone(0.20f, 0.85f, 0.35f)
                else -> Tone(0.10f, 0.90f, 0.30f)
            }
            else -> Tone(0f, 1f, 0f)
        }
    }

    /**
     * 12 âm bass "trượt" (808 glide): với mỗi cặp nốt khác nhau trong 4 nốt bass của [kit], một patch bắt đầu ở cao độ nốt
     * trước và trượt về nốt sau. Thứ tự khớp [Patterns.slideKitIdx]; dùng làm các ô kit 20..31 khi gửi sang engine.
     */
    fun slidePatches(kit: List<PadSpec>): List<Patch> {
        val bass = kit.drop((SoundCat.BASS.ordinal) * PADS_PER_ROW).take(PADS_PER_ROW)
        if (bass.size < PADS_PER_ROW) return List(Patterns.SLIDE_COUNT) { Patch.SILENT }
        val out = ArrayList<Patch>()
        for (from in 0 until PADS_PER_ROW) for (to in 0 until PADS_PER_ROW) {
            if (from == to) continue
            val target = patchFor(bass[to])
            val start = patchFor(bass[from])
            out.add(target.copy(fStart = start.fEnd, pitchTauMs = 80f))
        }
        return out
    }

    /** Patch của pad thứ [index] trong kit của thể loại [genreId], đã áp xử lý màu âm. */
    fun patchFor(genreId: String, index: Int, pad: PadSpec): Patch {
        val p = patchFor(pad)
        if (p.sample < 0) return p
        val cat = SoundCat.entries.getOrNull(index / PADS_PER_ROW) ?: return p
        val t = toneFor(genreId, cat)
        return p.copy(noiseLP = t.lowPass, gain = p.gain * t.gain, send = t.send)
    }

    /** Thông số của pad; âm không còn trong danh mục (dự án cũ) sẽ im lặng thay vì lỗi. */
    fun patchFor(pad: PadSpec): Patch =
        byId[pad.soundId]?.let { if (it.cat == SoundCat.BASS) it.patch.transposed(pad.semitones) else it.patch } ?: Patch.SILENT

    fun padLabel(pad: PadSpec): String {
        val snd = byId[pad.soundId] ?: return "?"
        return if (snd.cat == SoundCat.BASS) noteName(BASS_E1_MIDI + pad.semitones) else snd.label
    }

    private fun g(
        id: String, label: String, bpm: Int, family: String,
        kick: List<String>, snare: List<String>, hat: List<String>, perc: List<String>,
        bass: String, minor: Boolean, intervals: List<Int>,
    ) = Genre(
        id, label, bpm, family,
        mapOf(SoundCat.KICK to kick, SoundCat.SNARE to snare, SoundCat.HAT to hat, SoundCat.PERC to perc),
        bass, minor, intervals,
    )

    val genres: List<Genre> = listOf(
        g("ballad", "Ballad", 72, "slow",
            listOf("kick.soft", "kick.deep", "kick.tight", "kick.punch"),
            listOf("snare.soft", "snare.brush", "snare.rim", "snare.pop"),
            listOf("hat.soft", "hat.closed", "hat.open", "hat.shaker"),
            listOf("perc.tamb", "perc.tom", "perc.ride", "perc.crash"),
            "bass.sub", false, listOf(0, 4, 5, 7)),
        g("pop", "Pop", 110, "pop",
            listOf("kick.punch", "kick.tight", "kick.soft", "kick.edm"),
            listOf("snare.pop", "snare.crack", "snare.clap", "snare.rim"),
            listOf("hat.closed", "hat.open", "hat.tight", "hat.shaker"),
            listOf("perc.tamb", "perc.snap", "perc.shaker", "perc.crash"),
            "bass.finger", false, listOf(0, 5, 7, 9)),
        g("hiphop", "Hip-hop", 90, "boombap",
            listOf("kick.deep", "kick.punch", "kick.lofi", "kick.boom"),
            listOf("snare.crack", "snare.pop", "snare.lofi", "snare.rim"),
            listOf("hat.closed", "hat.open", "hat.lofi", "hat.tight"),
            listOf("perc.snap", "perc.cowbell", "perc.tom", "perc.crash"),
            "bass.808long", true, listOf(0, 3, 5, 7)),
        g("trap", "Trap / 808", 140, "trap",
            listOf("kick.boom", "kick.deep", "kick.punch", "kick.tight"),
            listOf("snare.trap", "snare.clap", "snare.crack", "snare.rim"),
            listOf("hat.trap", "hat.closed", "hat.open", "hat.tight"),
            listOf("perc.snap", "perc.cowbell", "perc.tom", "perc.crash"),
            "bass.808dist", true, listOf(0, 3, 7, 10)),
        g("melodic", "Melodic Trap", 140, "melodic",
            listOf("kick.boom", "kick.deep", "kick.soft", "kick.tight"),
            listOf("snare.clap", "snare.soft", "snare.trap", "snare.rim"),
            listOf("hat.soft", "hat.trap", "hat.tight", "hat.closed"),
            listOf("perc.snap", "perc.tom", "perc.shaker", "perc.crash"),
            "bass.808long", true, listOf(0, 3, 7, 10)),
        g("drill", "Drill", 142, "drill",
            listOf("kick.deep", "kick.boom", "kick.punch", "kick.tight"),
            listOf("snare.crack", "snare.trap", "snare.clap", "snare.rim"),
            listOf("hat.trap", "hat.tight", "hat.closed", "hat.open"),
            listOf("perc.snap", "perc.cowbell", "perc.tom", "perc.crash"),
            "bass.808dist", true, listOf(0, 3, 7, 10)),
        g("afro", "Afrobeats", 102, "afro",
            listOf("kick.tight", "kick.punch", "kick.soft", "kick.edm"),
            listOf("snare.rim", "snare.pop", "snare.soft", "snare.clap"),
            listOf("hat.shaker", "hat.tight", "hat.closed", "hat.soft"),
            listOf("perc.conga", "perc.bongo", "perc.shaker", "perc.cowbell"),
            "bass.pluck", false, listOf(0, 5, 7, 9)),
        g("lofi", "Lo-fi", 78, "boombap",
            listOf("kick.lofi", "kick.soft", "kick.deep", "kick.tight"),
            listOf("snare.lofi", "snare.soft", "snare.brush", "snare.rim"),
            listOf("hat.lofi", "hat.soft", "hat.closed", "hat.shaker"),
            listOf("perc.snap", "perc.shaker", "perc.tamb", "perc.clave"),
            "bass.sub", true, listOf(0, 3, 7, 10)),
        g("rnb", "R&B", 85, "slow",
            listOf("kick.soft", "kick.deep", "kick.tight", "kick.punch"),
            listOf("snare.clap", "snare.soft", "snare.pop", "snare.rim"),
            listOf("hat.soft", "hat.closed", "hat.open", "hat.tight"),
            listOf("perc.snap", "perc.tamb", "perc.shaker", "perc.cowbell"),
            "bass.808short", true, listOf(0, 3, 5, 7)),
        g("rock", "Rock", 120, "rock",
            listOf("kick.rock", "kick.punch", "kick.tight", "kick.deep"),
            listOf("snare.crack", "snare.pop", "snare.rim", "snare.soft"),
            listOf("hat.closed", "hat.open", "hat.tight", "hat.soft"),
            listOf("perc.crash", "perc.ride", "perc.tom", "perc.tamb"),
            "bass.finger", true, listOf(0, 3, 5, 7)),
        g("edm", "EDM", 128, "edm",
            listOf("kick.edm", "kick.punch", "kick.tight", "kick.boom"),
            listOf("snare.clap", "snare.crack", "snare.pop", "snare.trap"),
            listOf("hat.closed", "hat.open", "hat.trap", "hat.tight"),
            listOf("perc.crash", "perc.cowbell", "perc.shaker", "perc.tom"),
            "bass.synth", true, listOf(0, 3, 5, 7)),
        g("remix", "Remix", 126, "edm",
            listOf("kick.edm", "kick.punch", "kick.tight", "kick.boom"),
            listOf("snare.clap", "snare.crack", "snare.pop", "snare.trap"),
            listOf("hat.closed", "hat.open", "hat.trap", "hat.tight"),
            listOf("perc.crash", "perc.cowbell", "perc.shaker", "perc.tom"),
            "bass.synth", true, listOf(0, 3, 5, 7)),
        g("jazz", "Jazz", 100, "jazz",
            listOf("kick.soft", "kick.deep", "kick.tight", "kick.lofi"),
            listOf("snare.brush", "snare.soft", "snare.rim", "snare.pop"),
            listOf("hat.soft", "hat.closed", "hat.open", "hat.shaker"),
            listOf("perc.ride", "perc.shaker", "perc.conga", "perc.clave"),
            "bass.finger", true, listOf(0, 3, 7, 10)),
        g("acoustic", "Acoustic / Folk", 100, "pop",
            listOf("kick.soft", "kick.tight", "kick.deep", "kick.punch"),
            listOf("snare.soft", "snare.brush", "snare.rim", "snare.pop"),
            listOf("hat.soft", "hat.closed", "hat.open", "hat.shaker"),
            listOf("perc.tamb", "perc.shaker", "perc.conga", "perc.clave"),
            "bass.finger", false, listOf(0, 4, 5, 7)),
        g("latin", "Latin", 100, "latin",
            listOf("kick.tight", "kick.soft", "kick.punch", "kick.deep"),
            listOf("snare.rim", "snare.soft", "snare.pop", "snare.clap"),
            listOf("hat.closed", "hat.shaker", "hat.open", "hat.soft"),
            listOf("perc.conga", "perc.bongo", "perc.cowbell", "perc.clave"),
            "bass.finger", true, listOf(0, 5, 7, 10)),
    )

    fun genre(id: String): Genre? = genres.firstOrNull { it.id == id }
}
