package com.bandstudio.app

import com.bandstudio.app.model.Catalog
import com.bandstudio.app.model.Clip
import com.bandstudio.app.model.Instrument
import com.bandstudio.app.model.NoteEvent
import com.bandstudio.app.model.PAD_COUNT
import com.bandstudio.app.model.PADS_PER_ROW
import com.bandstudio.app.model.Patch
import com.bandstudio.app.model.Patterns
import com.bandstudio.app.model.Project
import com.bandstudio.app.model.SoundCat
import com.bandstudio.app.model.Quantizer
import com.bandstudio.app.model.Track
import com.bandstudio.app.model.encodeForEngine
import com.bandstudio.app.model.toJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun gridsCoverQuarterToThirtySecond() {
        assertEquals(listOf(960, 480, 240, 120), Quantizer.GRIDS)
        assertEquals(listOf("1/4", "1/8", "1/16", "1/32"), Quantizer.GRIDS.map { Quantizer.label(it) })
    }

    @Test
    fun snapStartRoundsToNearestGridLine() {
        assertEquals(240, Quantizer.snapStart(130, 240, 3840))
        assertEquals(0, Quantizer.snapStart(100, 240, 3840))
        assertEquals(120, Quantizer.snapStart(95, 120, 3840)) // lưới 1/32
        assertEquals(960, Quantizer.snapStart(700, 960, 3840)) // lưới 1/4
    }

    @Test
    fun snapStartWrapsToLoopStartAtClipEnd() {
        assertEquals(0, Quantizer.snapStart(3830, 240, 3840))
    }

    @Test
    fun snapDurationIsAtLeastOneStepAndCapped() {
        assertEquals(240, Quantizer.snapDuration(10, 240, 3840))
        assertEquals(480, Quantizer.snapDuration(400, 240, 3840))
        assertEquals(1000, Quantizer.snapDuration(5000, 240, 1000))
    }

    @Test
    fun quantizeNotesMergesDuplicatesAndSorts() {
        val notes = listOf(
            NoteEvent(500, 100, 62),
            NoteEvent(100, 90, 60),
            NoteEvent(20, 90, 60), // cùng cao độ, cùng ô lưới 0 -> gộp
        )
        val q = Quantizer.quantizeNotes(notes, 240, 3840)
        assertEquals(2, q.size)
        assertEquals(listOf(0, 480), q.map { it.start })
        assertTrue(q.all { it.start % 240 == 0 && it.dur % 240 == 0 })
    }

    @Test
    fun encodeForEngineLaysOutNotesInAbsoluteTicks() {
        val clip = Clip(id = 2, startBar = 1, bars = 1, notes = listOf(NoteEvent(0, 480, 60), NoteEvent(4000, 100, 62)))
        val project = Project("p", "n", tracks = listOf(Track(1, "Piano", Instrument.PIANO, clips = listOf(clip))))
        val data = project.encodeForEngine(loop = false, loopStart = 0, loopEnd = 7680, length = 7680)
        // Nốt thứ hai bắt đầu ngoài độ dài clip nên bị bỏ; nốt đầu ở tick tuyệt đối 3840.
        assertArrayEquals(intArrayOf(3, 0, 0, 7680, 7680, 1, 0, 0, 0, 1, 3840, 480, 60, 100), data)
    }

    @Test
    fun encodeForEngineClipsLongNotesToClipEnd() {
        val clip = Clip(id = 2, startBar = 0, bars = 1, notes = listOf(NoteEvent(3600, 1000, 60)))
        val project = Project("p", "n", tracks = listOf(Track(1, "Bass", Instrument.BASS, clips = listOf(clip))))
        val data = project.encodeForEngine(loop = true, loopStart = 0, loopEnd = 3840, length = 0)
        assertEquals(240, data[11]) // dur bị cắt từ 1000 xuống 240
    }

    @Test
    fun trackFreeSpaceRespectsOverlapAndTimelineEnd() {
        val t = Track(1, "Piano", Instrument.PIANO, clips = listOf(Clip(7, startBar = 2, bars = 2)))
        assertTrue(t.isFree(0, 2))
        assertFalse(t.isFree(1, 2))
        assertTrue(t.isFree(4, 1))
        assertTrue(t.isFree(2, 2, ignoreId = 7)) // chính clip đó thì được bỏ qua
        assertFalse(t.isFree(63, 2))
    }

    // ---- Looper: danh mục âm và thể loại ----------------------------------------------

    @Test
    fun everyGenreOffersFourDistinctMatchingSoundsPerRow() {
        assertTrue(Catalog.genres.size >= 10)
        assertEquals(Catalog.genres.size, Catalog.genres.map { it.id }.toSet().size)
        for (g in Catalog.genres) {
            val kit = g.kit()
            assertEquals("${g.id}: số âm trong kit", com.bandstudio.app.model.KIT_COUNT, kit.size)
            SoundCat.entries.filter { it != SoundCat.KEYS }.forEachIndexed { row, cat ->
                val rowPads = kit.subList(row * PADS_PER_ROW, (row + 1) * PADS_PER_ROW)
                for (pad in rowPads) {
                    val snd = Catalog.sound(pad.soundId)
                    assertTrue("${g.id}: thiếu âm ${pad.soundId}", snd != null)
                    assertEquals("${g.id}: sai hàng ${pad.soundId}", cat, snd!!.cat)
                }
                // Bốn lựa chọn phải khác nhau (hàng Bass cùng âm nhưng khác cao độ).
                assertEquals("${g.id}/${cat.label}: trùng lựa chọn", PADS_PER_ROW, rowPads.toSet().size)
            }
        }
    }

    @Test
    fun catalogParametersAreWithinEngineRanges() {
        assertEquals(Catalog.sounds.size, Catalog.sounds.map { it.id }.toSet().size)
        for (s in Catalog.sounds) {
            val p = s.patch
            val tag = s.id
            assertTrue("$tag: không có âm nào", p.toneAmp > 0f || p.noiseAmp > 0f)
            assertTrue("$tag amp", p.toneAmp in 0f..2f && p.noiseAmp in 0f..2f && p.tone2Amp in 0f..2f)
            assertTrue("$tag freq", p.fStart in 20f..20000f && p.fEnd in 20f..20000f)
            assertTrue("$tag tau", p.pitchTauMs > 0f && p.toneTauMs > 0f && p.noiseTauMs > 0f && p.tailTauMs > 0f)
            assertTrue("$tag filter", p.noiseHP in 0f..1f && p.noiseLP in 0f..1f)
            assertTrue("$tag drive/gain", p.drive in 0f..12f && p.gain in 0f..2f)
            assertTrue("$tag bursts", p.bursts in 0..8)
        }
        for (s in Catalog.sounds.filter { it.cat == SoundCat.BASS }) {
            assertTrue("${s.id}: bass phải có tone", s.patch.toneAmp > 0f)
        }
    }

    @Test
    fun everySoundIsUsedByAtLeastOneGenre() {
        val used = Catalog.genres.flatMap { it.kit() }.map { it.soundId }.toSet()
        val unused = Catalog.sounds.map { it.id }.filter { it !in used }
        assertTrue("âm không thể loại nào dùng: $unused", unused.isEmpty())
    }

    @Test
    fun bassPadsTransposeByEqualTemperament() {
        val root = Catalog.patchFor(com.bandstudio.app.model.PadSpec("bass.sub", 0))
        val octave = Catalog.patchFor(com.bandstudio.app.model.PadSpec("bass.sub", 12))
        assertEquals(root.fEnd * 2f, octave.fEnd, 1e-3f)
        assertEquals("E1", com.bandstudio.app.model.noteName(Catalog.BASS_E1_MIDI))
        assertEquals(Patch.SILENT, Catalog.patchFor(com.bandstudio.app.model.PadSpec("không-tồn-tại")))
    }

    @Test
    fun looperTrackEncodesKitThenNotes() {
        val genre = Catalog.genres.first()
        val clip = Clip(2, 0, 1, listOf(NoteEvent(480, 240, 5, 90)))
        val t = Track(1, "Looper", Instrument.LOOPER, clips = listOf(clip), genreId = genre.id, kit = genre.kit())
        val data = Project("p", "n", tracks = listOf(t)).encodeForEngine(false, 0, 0, 3840)
        assertEquals(3, data[0])
        assertEquals(Instrument.LOOPER.id, data[6])
        assertEquals(com.bandstudio.app.model.KIT_COUNT, data[7])
        var i = 8 + com.bandstudio.app.model.KIT_COUNT * Patch.INTS
        assertEquals(PAD_COUNT, data[i++])
        for (pad in 0 until PAD_COUNT) { // mỗi pad: len, số hit, rồi các hit
            assertEquals(Patterns.lenTicks(pad), data[i++])
            val n = data[i++]
            assertEquals(Patterns.padHits(genre, com.bandstudio.app.model.DEFAULT_KEY, pad).size, n)
            i += n * 4
        }
        assertEquals(1, data[i])
        assertArrayEquals(intArrayOf(480, 240, 5, 90), data.copyOfRange(i + 1, i + 5))
        assertEquals(i + 5, data.size)
    }

    @Test
    fun launcherModeKeepsOnlyPadPatterns() {
        val genre = Catalog.genre("trap")!!
        val clip = Clip(2, 0, 1, listOf(NoteEvent(0, 240, 5, 90)))
        val looper = Track(1, "L", Instrument.LOOPER, clips = listOf(clip), genreId = genre.id, kit = genre.kit())
        val piano = Track(2, "P", Instrument.PIANO, clips = listOf(clip))
        val p = Project("p", "n", tracks = listOf(piano, looper))
        val normal = p.encodeForEngine(false, 0, 0, 3840)
        val launcher = p.encodeForEngine(false, 0, 0, 0, launcherTrackId = looper.id)
        assertTrue(launcher.size < normal.size) // clip bị bỏ
        // Track 0 (piano) bắt đầu ở chỉ số 6: inst, kitCount, padCount, noteCount.
        assertEquals(0, launcher[7]); assertEquals(0, launcher[8]); assertEquals(0, launcher[9])
    }

    @Test
    fun looperKitSurvivesJsonRoundTrip() {
        val genre = Catalog.genre("trap")!!
        val t = Track(1, "Looper", Instrument.LOOPER, genreId = genre.id, kit = genre.kit())
        val p = Project("p", "n", tracks = listOf(t))
        val back = com.bandstudio.app.model.projectFromJson(org.json.JSONObject(p.toJson().toString()))
        assertEquals(genre.id, back.tracks[0].genreId)
        assertEquals(genre.kit(), back.tracks[0].kit)
    }

    @Test
    fun everyGenreFamilyHasPlayablePatternsForAllPads() {
        for (g in Catalog.genres) {
            assertTrue("${g.id}: họ ${g.family}", g.family in Patterns.familyIds)
            for (pad in 0 until com.bandstudio.app.model.KIT_COUNT) {
                val str = Patterns.pattern(g.family, pad)
                assertTrue("${g.family}/$pad", str != null && str.length == Patterns.STEPS)
                val hits = Patterns.hits(g.family, pad)
                assertTrue("${g.family}/$pad rỗng", hits.isNotEmpty())
                val row = pad / PADS_PER_ROW
                for (h in hits) {
                    assertTrue(h.tick in 0 until Patterns.LEN_TICKS && h.vel in 1..127)
                    // Hàng Bass chỉ chạm 4 nốt của hàng Bass; các hàng khác chạm đúng âm của pad.
                    if (row == SoundCat.BASS.ordinal) assertTrue(h.kitIdx in 16..19) else assertEquals(pad, h.kitIdx)
                }
            }
        }
    }

    @Test
    fun padsInARowDifferFromEachOther() {
        for (g in Catalog.genres) for (row in 0 until SoundCat.entries.size - 1) {
            val set = (0 until PADS_PER_ROW).map { Patterns.pattern(g.family, row * PADS_PER_ROW + it) }.toSet()
            assertEquals("${g.family}/$row: 4 mẫu phải khác nhau", PADS_PER_ROW, set.size)
        }
    }

    @Test
    fun keyboardPadsPlayChordsInKey() {
        for (g in Catalog.genres) for (key in 0 until 12) for (col in 0 until PADS_PER_ROW) {
            val pad = SoundCat.KEYS.ordinal * PADS_PER_ROW + col
            assertTrue(Patterns.isKeyPad(pad))
            val hits = Patterns.padHits(g, key, pad)
            assertTrue("${g.id}/$key/$col rỗng", hits.isNotEmpty())
            for (h in hits) {
                assertTrue(h.tick in 0 until Patterns.lenTicks(pad))
                assertTrue(h.kitIdx in 128 + 40..128 + 80)
                assertTrue(h.dur > 0 && h.vel in 1..127)
            }
            // Ô nhịp đầu phải bắt đầu bằng chủ âm của hợp âm đầu tiên.
            val first = Patterns.progression(g.minor, pad)[0]
            val pcs = hits.filter { it.tick < 40 }.map { (it.kitIdx - 128) % 12 }.toSet()
            val root = Math.floorMod(key + first.root, 12)
            assertTrue("${g.id}/$key: thiếu chủ âm", root in pcs || hits.first().kitIdx - 128 == 48 + root)
        }
    }

    @Test
    fun looperKeyRoundTripsAndMovesBass() {
        val g = Catalog.genre("trap")!!
        val t = Track(1, "L", Instrument.LOOPER, genreId = g.id, kit = g.kit(3), keyRoot = 3)
        val back = com.bandstudio.app.model.projectFromJson(org.json.JSONObject(Project("p", "n", tracks = listOf(t)).toJson().toString()))
        assertEquals(3, back.tracks[0].keyRoot)
        assertTrue(g.kit(3) != g.kit(4))
    }

    @Test
    fun swingDelaysOnlyOffbeatSixteenthsAndKeepsLoopLength() {
        val jazz = Catalog.genre("jazz")!!
        val straight = Patterns.hits(jazz.family, 8)   // hàng hi-hat
        val swung = Patterns.padHits(jazz, com.bandstudio.app.model.DEFAULT_KEY, 8)
        assertEquals(straight.size, swung.size)
        straight.zip(swung).forEach { (a, b) ->
            if (a.tick % 480 == 240) assertTrue(b.tick > a.tick && b.tick < Patterns.LEN_TICKS) else assertEquals(a.tick, b.tick)
        }
        // Thể loại không swing giữ nguyên.
        val pop = Catalog.genre("pop")!!
        assertEquals(Patterns.hits(pop.family, 8), Patterns.padHits(pop, com.bandstudio.app.model.DEFAULT_KEY, 8))
    }

    @Test
    fun hatAndPercAreDarkerAndQuieterInLofiThanKick() {
        val lofi = Catalog.genre("lofi")!!
        val kit = lofi.kit()
        val kick = Catalog.patchFor("lofi", 0, kit[0])
        val hat = Catalog.patchFor("lofi", 2 * PADS_PER_ROW, kit[2 * PADS_PER_ROW])
        val perc = Catalog.patchFor("lofi", 3 * PADS_PER_ROW, kit[3 * PADS_PER_ROW])
        assertEquals(1f, kick.gain, 0f)
        assertEquals(0f, kick.noiseLP, 0f)
        assertTrue(hat.gain < 0.7f && hat.noiseLP >= 0.4f)
        assertTrue(perc.gain < 0.7f && perc.noiseLP >= 0.4f)
        assertTrue(hat.sample >= 0 && perc.sample >= 0)
        // Snare: hạ nhẹ, làm tối chút và gửi reverb; kick/bass không gửi.
        val snare = Catalog.patchFor("lofi", PADS_PER_ROW, kit[PADS_PER_ROW])
        assertTrue(snare.send > 0.2f && snare.gain < 1f && snare.gain > 0.6f)
        assertEquals(0f, kick.send, 0f)
    }

    // ---- Mẫu âm thật ----

    @Test
    fun everyNonBassSoundHasAValidSampleFile() {
        assertEquals(Catalog.sampleIds.size, Catalog.sampleIds.toSet().size)
        for (snd in Catalog.sounds) {
            if (snd.cat == SoundCat.BASS) {
                assertEquals("${snd.id}: bass dùng tổng hợp", -1, snd.patch.sample)
                continue
            }
            assertEquals(Catalog.sampleIds.indexOf(snd.id), snd.patch.sample)
            val f = java.io.File("src/main/assets/samples/${snd.id}.wav") // thư mục chạy test là app/
            assertTrue("thiếu ${f.path}", f.exists())
            val wav = com.bandstudio.app.model.Wav.parse(f.readBytes())
            assertTrue("${snd.id}: không đọc được WAV", wav != null)
            wav!!
            assertEquals(44100, wav.sampleRate)
            assertTrue("${snd.id}: quá ngắn/dài (${wav.samples.size})", wav.samples.size in 400..(44100 * 2))
            val peak = wav.samples.maxOf { kotlin.math.abs(it) }
            assertTrue("${snd.id}: biên độ $peak", peak in 0.3f..1.0f)
            assertTrue("${snd.id}: không bắt đầu ngay (${wav.samples[0]})", kotlin.math.abs(wav.samples[0]) < 0.5f)
        }
    }

    @Test
    fun sampleSlotIsEncodedAsLastPatchInt() {
        val out = ArrayList<Int>()
        Patch(sample = 5, bursts = 2, chokeGroup = 1).encodeTo(out)
        assertEquals(Patch.INTS, out.size)
        assertEquals(5, out.last())
        assertEquals(2, out[9])   // bursts (số nguyên thô)
        assertEquals(1, out[14])  // chokeGroup (số nguyên thô)
    }

    @Test
    fun wavParserRejectsGarbage() {
        assertEquals(null, com.bandstudio.app.model.Wav.parse(ByteArray(10)))
        assertEquals(null, com.bandstudio.app.model.Wav.parse(ByteArray(100) { 7 }))
    }
}
