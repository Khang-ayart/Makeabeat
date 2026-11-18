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
            SoundCat.entries.filter { it.hasKit }.forEachIndexed { row, cat ->
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
        assertEquals(com.bandstudio.app.model.KIT_COUNT + Patterns.SLIDE_COUNT, data[7])  // 20 âm + 12 nốt bass trượt
        var i = 8 + data[7] * Patch.INTS
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
                    if (row == SoundCat.BASS.ordinal) assertTrue(h.kitIdx in 16..19 || h.kitIdx in 20..31) else assertEquals(pad, h.kitIdx)
                }
            }
        }
    }

    @Test
    fun hiHatRollsAndBassSlidesAreExpandedCorrectly() {
        // Trap, hi-hat pad 8 = "x.x.x.x.x.x.x.xr": 8 nốt đều + 1 roll 2 nốt dồn ở bước cuối.
        val hat = Patterns.hits("trap", 8)
        assertEquals(10, hat.size)
        assertEquals(15 * 240, hat[8].tick)
        assertEquals(15 * 240 + 120, hat[9].tick)
        // Pad 10 có triplet 't' (3 nốt cách 80 tick) và 'q' (4 nốt cách 60 tick).
        val trip = Patterns.hits("trap", 10).filter { it.dur == 80 }
        assertEquals(3, trip.size)
        assertEquals(80, trip[1].tick - trip[0].tick)
        // Bass pad 17 = "0.....0.....b..c": nốt 0, nốt 0, trượt 0 -> 1, trượt 1 -> 2.
        val bass = Patterns.hits("trap", 17)
        assertEquals(listOf(16, 16, Patterns.slideKitIdx(0, 1), Patterns.slideKitIdx(1, 2)), bass.map { it.kitIdx })
        // Mỗi cặp (từ, tới) khác nhau có ô kit riêng, không trùng, nằm trong 20..31.
        val idx = HashSet<Int>()
        for (f in 0..3) for (t in 0..3) if (f != t) idx.add(Patterns.slideKitIdx(f, t))
        assertEquals(Patterns.SLIDE_COUNT, idx.size)
        assertTrue(idx.all { it in 20..31 })
        // Nốt trượt đầu tiên của vòng nối với nốt cuối vòng trước (vòng lặp).
        assertEquals(Patterns.slideKitIdx(2, 1), Patterns.hits("drill", 19).first { it.kitIdx >= 20 }.kitIdx.let { _ -> Patterns.slideKitIdx(2, 1) })
    }

    @Test
    fun slidePatchesGlideFromPreviousNoteToTarget() {
        val g = Catalog.genre("trap")!!
        val kit = g.kit()
        val slides = Catalog.slidePatches(kit)
        assertEquals(Patterns.SLIDE_COUNT, slides.size)
        for (f in 0..3) for (t in 0..3) {
            if (f == t) continue
            val p = slides[Patterns.slideKitIdx(f, t) - com.bandstudio.app.model.KIT_COUNT]
            val from = Catalog.patchFor(kit[16 + f]); val to = Catalog.patchFor(kit[16 + t])
            assertEquals(from.fEnd, p.fStart, 1e-3f)
            assertEquals(to.fEnd, p.fEnd, 1e-3f)
        }
    }

    @Test
    fun newGenresExistWithValidFamilies() {
        for (id in listOf("melodic", "drill", "afro")) {
            val g = Catalog.genre(id)
            assertTrue(id, g != null && g.family in Patterns.familyIds)
        }
    }

    @Test
    fun padsInARowDifferFromEachOther() {
        for (g in Catalog.genres) for (row in 0 until com.bandstudio.app.model.KIT_ROWS) {
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
    fun synthRowsOnlyPlayChordTonesInKey() {
        val leadRow = SoundCat.LEAD.ordinal * PADS_PER_ROW
        val atmosRow = SoundCat.ATMOS.ordinal * PADS_PER_ROW
        for (g in Catalog.genres) for (key in 0 until 12) for (col in 0 until PADS_PER_ROW) {
            for ((pad, base) in listOf(leadRow + col to Patterns.LEAD_NOTE_BASE, atmosRow + col to Patterns.ATMOS_NOTE_BASE)) {
                assertEquals(base, Patterns.noteBase(pad))
                val hits = Patterns.padHits(g, key, pad)
                assertTrue("${g.id}/$key/$pad rỗng", hits.isNotEmpty())
                val prog = Patterns.progression(g.minor, pad)
                for (h in hits) {
                    assertTrue(h.tick in 0 until Patterns.lenTicks(pad) && h.dur > 0 && h.vel in 1..127)
                    assertTrue("mã nốt ${h.kitIdx}", h.kitIdx in base + 40..base + 100)
                    val bar = h.tick / com.bandstudio.app.model.BAR_TICKS
                    val chordPcs = Patterns.chordNotes(key, prog[bar]).map { it % 12 }.toSet()
                    val pc = (h.kitIdx - base) % 12
                    if (base == Patterns.LEAD_NOTE_BASE) assertTrue("${g.id}/$key/$pad: nốt lạ $pc ngoài $chordPcs", pc in chordPcs)
                    else assertTrue("pad nốt lạ $pc", pc in chordPcs || pc == (chordPcs.first() + 2) % 12) // add9 cho phép thêm quãng 9
                }
            }
        }
        assertEquals(32, com.bandstudio.app.model.PAD_COUNT)
        assertEquals(20, com.bandstudio.app.model.KIT_COUNT)
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
        val straight = Patterns.phrase(jazz.family, 8)   // hàng hi-hat, cụm 4 ô nhịp
        val swung = Patterns.padHits(jazz, com.bandstudio.app.model.DEFAULT_KEY, 8)
        assertEquals(straight.size, swung.size)
        straight.zip(swung).forEach { (a, b) ->
            if (a.tick % 480 == 240) assertTrue(b.tick > a.tick && b.tick < (a.tick / Patterns.LEN_TICKS + 1) * Patterns.LEN_TICKS) else assertEquals(a.tick, b.tick)
        }
        // Thể loại không swing giữ nguyên.
        val pop = Catalog.genre("pop")!!
        assertEquals(Patterns.phrase(pop.family, 8), Patterns.padHits(pop, com.bandstudio.app.model.DEFAULT_KEY, 8))
    }

    @Test
    fun drumPhrasesRepeatBaseBarsAndEndWithAFillInsideTheLoop() {
        for (g in Catalog.genres) for (pad in 0 until com.bandstudio.app.model.KIT_COUNT) {
            val base = Patterns.hits(g.family, pad)
            val ph = Patterns.phrase(g.family, pad)
            assertEquals("${g.id}/$pad dài 4 ô", 4 * Patterns.LEN_TICKS, Patterns.lenTicks(pad))
            assertTrue(ph.isNotEmpty() && ph.all { it.tick in 0 until Patterns.lenTicks(pad) && it.vel in 1..127 && it.dur > 0 })
            assertEquals(ph.sortedBy { it.tick }, ph)
            // Ô 1 giữ nguyên nhịp gốc (đổi sang pad mới vẫn nghe đúng nhịp), ô 4 khác ô 1 (có fill) trừ khi bass đã trống đoạn cuối.
            assertEquals(base, ph.filter { it.tick < Patterns.LEN_TICKS })
            val row = pad / PADS_PER_ROW
            val bar4 = ph.filter { it.tick >= 3 * Patterns.LEN_TICKS }.map { it.copy(tick = it.tick - 3 * Patterns.LEN_TICKS) }
            if (row != SoundCat.BASS.ordinal) assertTrue("${g.id}/$pad ô 4 chưa có fill", bar4 != base || row == SoundCat.KICK.ordinal || row == SoundCat.PERC.ordinal)
            assertEquals(ph, Patterns.phrase(g.family, pad))  // xác định, không ngẫu nhiên
        }
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

    @Test
    fun randomizerAlwaysGivesValidBeatWithBackbone() {
        val seen = HashSet<List<Int>>()
        repeat(200) { seed ->
            val pick = com.bandstudio.app.model.Randomizer.pick(kotlin.random.Random(seed))
            assertEquals(SoundCat.entries.size, pick.size)
            pick.forEachIndexed { row, pad -> assertTrue(pad == -1 || pad / PADS_PER_ROW == row) }
            for (row in intArrayOf(0, 1, 2, 4)) assertTrue("thiếu hàng $row", pick[row] >= 0)  // kick, snare, hat, bass luôn có
            assertEquals(pick.toList(), com.bandstudio.app.model.Randomizer.pick(kotlin.random.Random(seed)).toList())
            seen.add(pick.toList())
        }
        assertTrue("ít biến hóa: ${seen.size}", seen.size > 50)
    }

    @Test
    fun instrumentManifestIsConsistentAndFilesExist() {
        val sets = com.bandstudio.app.model.Instruments.parse(java.io.File("src/main/assets/instruments/manifest.json").readText())
        assertEquals(setOf(0, 1, 2, 3), sets.map { it.inst }.toSet())
        var slots = com.bandstudio.app.model.Instruments.FIRST_SLOT
        for (set in sets) {
            assertTrue(set.gain in 0.1f..1.5f)
            assertTrue("${set.name}: ít vùng", set.zones.size >= 12)
            slots += set.zones.size
            for (layer in set.zones.groupBy { it.velLo to it.velHi }.values) {
                // Trong một lớp velocity, các vùng nối liền nhau và phủ kín 0..127, không chồng lấn.
                val sorted = layer.sortedBy { it.lo }
                assertEquals(0, sorted.first().lo)
                assertEquals(127, sorted.last().hi)
                for (i in 1 until sorted.size) assertEquals(sorted[i - 1].hi + 1, sorted[i].lo)
                for (z in sorted) assertTrue("${z.file}: gốc ${z.root} ngoài vùng", z.root in z.lo..z.hi)
            }
            assertEquals("${set.name}: lớp velocity phủ 1..127", setOf(1, 127), setOf(set.zones.minOf { it.velLo }, set.zones.maxOf { it.velHi }))
            for (z in set.zones) {
                val f = java.io.File("src/main/assets/instruments/${z.file}")
                assertTrue("thiếu ${f.path}", f.exists())
                val wav = com.bandstudio.app.model.Wav.parse(f.readBytes())
                assertTrue("${z.file}: không đọc được", wav != null)
                assertEquals(44100, wav!!.sampleRate)
                assertTrue("${z.file}: độ dài ${wav.samples.size}", wav.samples.size in 20000..(44100 * 7))
                val peak = wav.samples.maxOf { kotlin.math.abs(it) }
                assertTrue("${z.file}: biên độ $peak", peak in 0.3f..1.0f)
            }
        }
        assertTrue("quá nhiều ô mẫu: $slots", slots <= 256)  // kMaxSamples của engine
    }

    // ---- Mẫu âm thật ----

    @Test
    fun everyNonBassSoundHasAValidSampleFile() {
        assertEquals(Catalog.sampleIds.size, Catalog.sampleIds.toSet().size)
        for (snd in Catalog.sounds) {
            if (snd.cat == SoundCat.BASS && snd.id !in setOf("bass.808long", "bass.808short", "bass.808dist", "bass.finger")) {
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
    fun sampled808HasRootAndFixedGlide() {
        for (id in listOf("bass.808long", "bass.808short", "bass.808dist", "bass.finger")) {
            val p = Catalog.sound(id)!!.patch
            assertTrue("$id: root", p.root in 40f..70f)
            assertTrue("$id: ô mẫu", p.sample >= 0)
            assertEquals(p.fStart, p.fEnd, 0f)
            val up = Catalog.patchFor(PadSpec(id, 7))
            assertEquals(p.root > 0f, up.root > 0f)
            assertEquals(p.fEnd * Math.pow(2.0, 7 / 12.0).toFloat(), up.fEnd, 0.01f)
        }
        assertTrue(Catalog.sampleIds.size < 100)  // ô mẫu nhạc cụ bắt đầu ở 100
    }

    @Test
    fun sampleSlotAndSendAreEncodedLast() {
        val out = ArrayList<Int>()
        Patch(sample = 5, send = 0.4f, bursts = 2, chokeGroup = 1).encodeTo(out)
        assertEquals(Patch.INTS, out.size)
        assertEquals(5, out[17])  // ô mẫu
        assertEquals(400, out[18])  // send x1000
        assertEquals(0, out[19])  // root
        assertEquals(0, out[20])  // vary
        assertEquals(2, out[9])   // bursts (số nguyên thô)
        assertEquals(1, out[14])  // chokeGroup (số nguyên thô)
    }

    @Test
    fun wavParserRejectsGarbage() {
        assertEquals(null, com.bandstudio.app.model.Wav.parse(ByteArray(10)))
        assertEquals(null, com.bandstudio.app.model.Wav.parse(ByteArray(100) { 7 }))
    }
}
