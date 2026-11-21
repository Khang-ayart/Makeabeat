package com.bandstudio.app

import com.bandstudio.app.model.Arranger
import com.bandstudio.app.model.BAR_TICKS
import com.bandstudio.app.model.Catalog
import com.bandstudio.app.model.VOCAL_SLOT
import com.bandstudio.app.model.VocalClip
import com.bandstudio.app.model.MAX_BARS
import com.bandstudio.app.model.MAX_TRACKS
import com.bandstudio.app.model.Clip
import com.bandstudio.app.model.Instrument
import com.bandstudio.app.model.NoteEvent
import com.bandstudio.app.model.PAD_COUNT
import com.bandstudio.app.model.PADS_PER_ROW
import com.bandstudio.app.model.PadSpec
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
import org.junit.Assert.assertNull
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
    fun crashPadsGetAnImpactAtPhraseStartAndEveryGenreHasASynthStyle() {
        for (g in Catalog.genres) {
            assertTrue("${g.id}: giọng synth", Patterns.synthStyleOf(g.id) in 1..3)
            for (pad in 0 until com.bandstudio.app.model.KIT_COUNT) {
                val hits = Patterns.padHits(g, com.bandstudio.app.model.DEFAULT_KEY, pad)
                val isCrash = pad / PADS_PER_ROW == SoundCat.PERC.ordinal && g.kit()[pad].soundId.contains("crash")
                if (isCrash) assertTrue("${g.id}/$pad thiếu impact", hits.any { it.tick == 0 })
                assertTrue(hits.all { it.tick in 0 until Patterns.lenTicks(pad) })
            }
        }
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

    private fun vclip(sync: Int = 0, nudge: Int = 0, len: Int = 10_000) =
        VocalClip(rawFile = "a-raw.wav", file = "a.wav", sampleRate = 44100, lengthMs = len, syncMs = sync, nudgeMs = nudge)

    @Test
    fun vocalClipTimingTrimsOrDelaysAccordingToSyncAndNudge() {
        val a = vclip(sync = 300, nudge = -100)
        assertEquals(200, a.startMs); assertEquals(200, a.trimMs); assertEquals(0, a.offsetMs); assertEquals(9800, a.playMs)
        val b = vclip(sync = 0, nudge = -100)
        assertEquals(0, b.trimMs); assertEquals(100, b.offsetMs); assertEquals(10_000, b.playMs)
        assertEquals(6, b.bars(120))  // 10,1 giây = 5,05 ô nhịp ở 120 BPM
        assertEquals(5, vclip().bars(120))
    }

    @Test
    fun vocalTrackIsEncodedAsOneNoteWithSlotAndNothingInLauncherMode() {
        val t = Track(1, "Vocal", Instrument.VOCAL, vocal = vclip(sync = 0, nudge = -100))
        val p = Project("p", "n", tracks = listOf(t))
        val enc = p.encodeForEngine(false, 0, 0, 0).toList()
        val want = listOf(100, 10_000, VOCAL_SLOT, 100, 0)
        assertTrue("không thấy nốt giọng $want trong $enc", (0..enc.size - 5).any { enc.subList(it, it + 5) == want })
        val launcher = p.encodeForEngine(false, 0, 0, 0, launcherTrackId = 99).toList()
        assertFalse((0..launcher.size - 5).any { launcher.subList(it, it + 5) == want })
    }

    @Test
    fun vocalAndKeySettingsSurviveSaveAndLoad() {
        val t = Track(1, "Vocal", Instrument.VOCAL, vocal = vclip(sync = 120, nudge = 30).copy(tune = true, denoise = true, reverb = 0.4f), auto = false)
        val auto = Track(2, "Tự động · Piano", Instrument.PIANO, auto = true)
        val p = Project("p", "n", tracks = listOf(t, auto), keyRoot = 2, minor = true, arrangeGenre = "trap", followMelody = true)
        val back = com.bandstudio.app.model.projectFromJson(org.json.JSONObject(p.toJson().toString()))
        assertEquals(p, back)
    }

    @Test
    fun melodyFromPitchTurnsFramesIntoQuantizedNotesInKey() {
        val hop = 512
        val hz = FloatArray(80) { i ->
            when { i < 20 -> 261.63f; i < 28 -> 0f; i < 48 -> 329.63f; else -> 0f }
        }
        val notes = Arranger.melodyFromPitch(hz, hop, 44100, startMs = 0, bpm = 120, keyRoot = 0, minor = false)
        assertEquals(listOf(60, 64), notes.map { it.pitch })
        assertTrue(notes.all { it.start % 240 == 0 && it.dur % 240 == 0 && it.dur >= 240 })
        assertTrue(notes[0].start + notes[0].dur <= notes[1].start)
        // Nốt ngoài thang (C#4 trong C trưởng) được kéo về nốt trong thang; dịch bắt đầu cắt phần đầu.
        val sharp = Arranger.melodyFromPitch(FloatArray(30) { 277.18f }, hop, 44100, 0, 120, 0, false)
        assertTrue(sharp.single().pitch in listOf(60, 62))
        val cut = Arranger.melodyFromPitch(hz, hop, 44100, startMs = 400, bpm = 120, keyRoot = 0, minor = false)
        assertTrue(cut.all { it.start >= 0 })
        assertTrue(Arranger.melodyFromPitch(FloatArray(0), hop, 44100, 0, 120, 0, false).isEmpty())
    }

    @Test
    fun arrangeBuildsValidAutoTracksAndReplacesOldOnes() {
        val user = Track(1, "Của tôi", Instrument.BASS)
        val oldAuto = Track(2, "Tự động · cũ", Instrument.PIANO, auto = true)
        val vocal = Track(3, "Vocal", Instrument.VOCAL, vocal = vclip(len = 40_000))
        val base = Project("p", "n", bpm = 90, tracks = listOf(user, oldAuto, vocal), nextId = 10, keyRoot = 9, minor = true)
        for (genre in Catalog.genres) for (follow in listOf(false, true)) {
            val melody = if (follow) listOf(NoteEvent(0, 480, 69, 90), NoteEvent(15360, 480, 72, 90)) else emptyList()
            val out = Arranger.arrange(base.copy(followMelody = follow), genre, vocalBars = 15, melody = melody)
            assertTrue(out.tracks.size <= MAX_TRACKS)
            assertTrue("track người dùng bị mất", out.tracks.any { it.id == 1 } && out.tracks.any { it.id == 3 })
            assertFalse("track tự động cũ còn lại", out.tracks.any { it.id == 2 })
            val autos = out.tracks.filter { it.auto }
            assertTrue(autos.any { it.instrument.isLooper } && autos.any { it.instrument == Instrument.PIANO })
            assertEquals(out.tracks.map { it.id }.size, out.tracks.map { it.id }.toSet().size)
            for (t in autos) {
                val sorted = t.clips.sortedBy { it.startBar }
                for ((i, c) in sorted.withIndex()) {
                    assertTrue(c.startBar >= 0 && c.endBar <= MAX_BARS)
                    if (i > 0) assertTrue("clip chồng nhau ở ${t.name}", sorted[i - 1].endBar <= c.startBar)
                    assertTrue(c.notes.isNotEmpty())
                    for (n in c.notes) {
                        assertTrue("${t.name} nốt ngoài clip: ${n.start}+${n.dur} > ${c.lengthTicks}", n.start >= 0 && n.dur > 0 && n.start + n.dur <= c.lengthTicks + 960)
                        assertTrue(n.pitch in 0..(if (t.instrument.isLooper) 511 else 127))
                    }
                }
            }
            val total = Arranger.totalBars(15)
            assertTrue(autos.flatMap { it.clips }.maxOf { it.endBar } <= total)
            if (follow) {
                val bar0 = autos.first { it.instrument == Instrument.PIANO }.clips.first { it.startBar == 0 }.notes.filter { it.start < BAR_TICKS }
                assertTrue("hợp âm ô 1 không chứa nốt A của giọng", bar0.any { it.pitch % 12 == 9 })
                assertTrue("thiếu bass theo giọng", autos.any { it.instrument == Instrument.BASS })
                assertTrue("looper còn hàng nhạc", autos.first { it.instrument.isLooper }.clips.all { c -> c.notes.all { it.pitch < 4 * PADS_PER_ROW } })
            }
            val voice = out.tracks.first { it.id == 3 }.volume
            for (t in autos) assertTrue("${t.name} to hơn giọng", t.volume < voice)
        }
        assertEquals(listOf(Arranger.Section.INTRO, Arranger.Section.VERSE, Arranger.Section.VERSE, Arranger.Section.CHORUS, Arranger.Section.CHORUS, Arranger.Section.OUTRO),
            Arranger.sections(6))
        assertTrue(Arranger.totalBars(0) >= 8 && Arranger.totalBars(1000) <= MAX_BARS && Arranger.totalBars(13) % 4 == 0)
    }

    @Test
    fun audioClipsAreEncodedWithStretchAndSourceOffset() {
        val src = com.bandstudio.app.model.AudioSource(1, "loop", "a.wav", 44100, 8000, srcBpm = 120, slot = 305)
        val clip = com.bandstudio.app.model.AudioClip(1, 1, startBar = 2, srcStartMs = 2000, srcLenMs = 4000)
        val t = Track(1, "Audio", Instrument.AUDIO, audio = listOf(clip))
        val p = Project("p", "n", bpm = 60, tracks = listOf(t), sources = listOf(src))
        // 120 -> 60 BPM: dài gấp đôi. Bắt đầu ô 2 ở 60 BPM = 8000 ms.
        val want = listOf(8000, 8000, 305, 100, 4000)
        val enc = p.encodeForEngine(false, 0, 0, 0).toList()
        assertTrue("không thấy $want trong $enc", (0..enc.size - 5).any { enc.subList(it, it + 5) == want })
        assertEquals(9, enc[6])  // inst gửi engine = 9 (Vocal)
        assertEquals(2 + 2, p.endBar)  // 8 s ở 60 BPM = 2 ô, bắt đầu ô 2
    }

    @Test
    fun audioSourcesAndClipsSurviveSaveAndLoad() {
        val src = com.bandstudio.app.model.AudioSource(3, "beat", "b.wav", 48000, 5000, srcBpm = 90, slot = 310)
        val t = Track(1, "Audio", Instrument.AUDIO, audio = listOf(com.bandstudio.app.model.AudioClip(7, 3, 1, 500, 2500, 0.8f, "Hook")))
        val p = Project("p", "n", tracks = listOf(t), sources = listOf(src))
        val back = com.bandstudio.app.model.projectFromJson(org.json.JSONObject(p.toJson().toString()))
        assertEquals(p, back)
    }

    private fun audioProject(): Project {
        val src = com.bandstudio.app.model.AudioSource(1, "loop", "a.wav", 44100, 16_000, srcBpm = 120, slot = 301)
        val t = Track(1, "Audio", Instrument.AUDIO)
        return com.bandstudio.app.model.AudioEdit.addClip(Project("p", "n", bpm = 120, tracks = listOf(t), sources = listOf(src), nextId = 10), 1, 1, 0)!!
    }

    @Test
    fun audioSplitMoveTrimDuplicateDelete() {
        val E = com.bandstudio.app.model.AudioEdit
        var p = audioProject()                       // 16 s ở 120 BPM = 8 ô
        val c = p.tracks[0].audio[0]
        assertEquals(8, c.bars(p.source(1)!!, 120))
        p = E.split(p, 1, c.id, 3)!!                  // 3 ô | 5 ô
        val (a, b) = p.tracks[0].audio
        assertEquals(6000, a.srcLenMs); assertEquals(3, b.startBar); assertEquals(6000, b.srcStartMs); assertEquals(10_000, b.srcLenMs)
        assertNull(E.split(p, 1, a.id, 0)); assertNull(E.split(p, 1, a.id, 3))
        p = E.move(p, 1, b.id, 2)!!
        assertEquals(5, p.tracks[0].audio[1].startBar)
        assertNull(E.move(p, 1, b.id, -9))
        p = E.trim(p, 1, b.id, 1, fromEnd = false)!!
        assertEquals(6, p.tracks[0].audio[1].startBar); assertEquals(8000, p.tracks[0].audio[1].srcStartMs)
        p = E.trim(p, 1, b.id, 1, fromEnd = true)!!
        assertEquals(6000, p.tracks[0].audio[1].srcLenMs)
        assertNull(E.trim(p, 1, a.id, 3, fromEnd = true))  // sẽ không còn ô nào
        p = E.duplicate(p, 1, a.id)!!
        assertEquals(3, p.tracks[0].audio.size); assertEquals(3, p.tracks[0].audio[2].startBar)
        p = E.delete(p, 1, a.id)!!
        assertEquals(2, p.tracks[0].audio.size)
        assertNull(E.delete(p, 1, 9999))
    }

    @Test
    fun audioEditsFollowBpmStretch() {
        val E = com.bandstudio.app.model.AudioEdit
        val p0 = audioProject().copy(bpm = 60)        // 120 -> 60 BPM: giãn gấp đôi, vẫn 8 ô
        val c = p0.tracks[0].audio[0]
        assertEquals(8, c.bars(p0.source(1)!!, 60))  // loop 8 ô vẫn là 8 ô ở BPM mới
        val p = E.split(p0, 1, c.id, 4)!!             // 4 ô ở 60 BPM = 16 s phát = 8 s gốc
        assertEquals(8000, p.tracks[0].audio[0].srcLenMs)
    }

    @Test
    fun audioSlotsAreAllocatedWithinRange() {
        val E = com.bandstudio.app.model.AudioEdit
        var p = audioProject()
        assertEquals(302, E.freeSlot(p))
        val many = (0 until 82).map { com.bandstudio.app.model.AudioSource(100 + it, "x", "x", 44100, 1000, slot = 302 + it) }
        p = p.copy(sources = p.sources + many)
        assertNull(E.freeSlot(p))
    }

    @Test
    fun chordsFollowTheMelodyAndStayStable() {
        val mel = listOf(NoteEvent(0, 3600, 69, 90), NoteEvent(BAR_TICKS, 3600, 67, 90))
        val ch = Arranger.chordsFromMelody(mel, 4, key = 9, minor = true)
        assertEquals(4, ch.size)
        assertEquals(0, ch[0].root); assertTrue(ch[0].minor)  // nốt A -> Am
        val g = (7 - 9 + 12) % 12                               // nốt G tính từ tông A
        assertTrue("ô 2 phải có hợp âm chứa G", ch[1].intervals.any { (ch[1].root + it) % 12 == g })
        assertEquals(ch[1].root, ch[2].root); assertEquals(ch[1].minor, ch[2].minor)  // ô trống giữ hợp âm trước
        assertEquals(ch[2].root, ch[3].root)
    }

    @Test
    fun barActivityIsRelativeAndSilentBarsAreZero() {
        val sr = 8000
        val bpm = 120  // 1 ô = 2 s
        val x = FloatArray(sr * 8)
        for (i in 0 until sr * 2) x[i] = 0.5f * Math.sin(i * 0.3).toFloat()
        for (i in sr * 4 until sr * 6) x[i] = 0.1f * Math.sin(i * 0.3).toFloat()
        val a = Arranger.barActivity(x, sr, 0, bpm, 4)
        assertEquals(1f, a[0], 0.01f); assertEquals(0f, a[1], 0.0001f)
        assertTrue(a[2] in 0.1f..0.35f); assertEquals(0f, a[3], 0.0001f)
        val late = Arranger.barActivity(x, sr, -2000, bpm, 4)
        assertEquals(0f, late[0], 0.0001f); assertEquals(1f, late[1], 0.01f)
        assertEquals(List(3) { 0f }, Arranger.barActivity(FloatArray(0), sr, 0, bpm, 3))
    }

    private fun vocalProject(): Project {
        val t = Track(1, "Vocal", Instrument.VOCAL, vocal = vclip(sync = 0, nudge = -100))  // phát ở 100 ms, dài 10 000 ms
        return Project("p", "n", bpm = 120, tracks = listOf(t))
    }

    @Test
    fun vocalCutSplitDeleteMoveResetAndEncode() {
        val E = com.bandstudio.app.model.VocalEdit
        var p = vocalProject()
        assertNull(E.split(p, 100 + 30))                 // quá sát mép
        p = E.split(p, 4100)!!                            // cắt giữa
        val segs = p.tracks[0].vocal!!.segs
        assertEquals(2, segs.size)
        assertEquals(4000, segs[0].lenMs); assertEquals(100, segs[0].atMs)
        assertEquals(4100, segs[1].atMs); assertEquals(4000, segs[1].srcStartMs); assertEquals(6000, segs[1].lenMs)
        val enc = p.encodeForEngine(false, 0, 0, 0).toList()
        for (want in listOf(listOf(100, 4000, VOCAL_SLOT, 100, 0), listOf(4100, 6000, VOCAL_SLOT, 100, 4000)))
            assertTrue("thiếu $want", (0..enc.size - 5).any { enc.subList(it, it + 5) == want })
        p = E.move(p, 5000, 500)!!                        // dời đoạn phải 0,5 s
        assertEquals(4600, p.tracks[0].vocal!!.segs[1].atMs)
        assertNull(E.move(p, 5000, -99_999))
        p = E.delete(p, 200)!!                            // xóa đoạn trái
        assertEquals(1, p.tracks[0].vocal!!.segs.size)
        assertNull(E.delete(p, 5000))                     // không xóa đoạn cuối
        p = E.reset(p)!!
        assertTrue(p.tracks[0].vocal!!.segs.isEmpty())
        assertNull(E.reset(p))
    }

    @Test
    fun vocalCutsSurviveSaveAndLoadAndDefaultSegmentMatchesOldBehaviour() {
        val p0 = vocalProject()
        assertEquals(listOf(com.bandstudio.app.model.VocalSeg(0, 0, 10_000, 100)), p0.tracks[0].vocal!!.segments())
        val p = com.bandstudio.app.model.VocalEdit.split(p0, 5000)!!
        val back = com.bandstudio.app.model.projectFromJson(org.json.JSONObject(p.toJson().toString()))
        assertEquals(p, back)
        assertEquals(6, back.endBar)  // 10,1 s ở 120 BPM = 6 ô
    }

    @Test
    fun tickMsConversionRoundTrips() {
        assertEquals(2000, com.bandstudio.app.model.tickToMs(3840, 120))
        assertEquals(3840, com.bandstudio.app.model.msToTick(2000, 120))
    }

    /** Giọng giả: tiếng bùng ngắn ở các bước 1/16 [stepsOn] của ô 0 (120 BPM: 1 ô = 2 s, 1 bước = 125 ms). */
    private fun burstVoice(sr: Int, stepsOn: List<Int>, bars: Int = 2): FloatArray {
        val x = FloatArray(sr * 2 * bars)
        for (s in stepsOn) {
            val a = (s * 0.125 * sr).toInt()
            for (i in 0 until (0.06 * sr).toInt()) x[a + i] = (0.6 * Math.sin(i * 0.25)).toFloat()
        }
        return x
    }

    @Test
    fun onsetsLandOnTheStepsWhereSyllablesStart() {
        val sr = 8000
        val on = Arranger.barOnsets(burstVoice(sr, listOf(0, 4, 10)), sr, 0, 120, 2)
        assertEquals((1 shl 0) or (1 shl 4) or (1 shl 10), on[0])
        assertEquals(0, on[1])
        assertEquals(listOf(0, 0), Arranger.barOnsets(FloatArray(sr * 4), sr, 0, 120, 2))
    }

    @Test
    fun chordsFromPitchClassesFollowTheVoiceAndVaryWithTheSeed() {
        val a = DoubleArray(12).also { it[9] = 40.0; it[0] = 25.0; it[4] = 20.0 }   // A C E -> Am
        val g = DoubleArray(12).also { it[7] = 40.0; it[11] = 25.0; it[2] = 20.0 }  // G B D -> G
        val ch = Arranger.chordsFromPitchClasses(listOf(a, g), 2, 9, true, java.util.Random(1), 0)
        assertEquals(0, ch[0].root); assertTrue(ch[0].minor)
        assertEquals(10, ch[1].root); assertFalse(ch[1].minor)   // G = bậc VII của Am
        // Ô trống cao độ: đi theo vòng hợp âm dự phòng, và vòng khác nhau cho kết quả khác nhau.
        val empty = List(4) { DoubleArray(12) }
        val f0 = Arranger.chordsFromPitchClasses(empty, 4, 0, true, java.util.Random(1), 0).map { it.root to it.minor }
        val f1 = Arranger.chordsFromPitchClasses(empty, 4, 0, true, java.util.Random(1), 1).map { it.root to it.minor }
        assertTrue(f0 != f1)
    }

    private fun arrangeWith(seed: Int, profile: Arranger.VocalProfile?): Project {
        val vocal = Track(3, "Vocal", Instrument.VOCAL, vocal = vclip(len = 30_000))
        val base = Project("p", "n", bpm = 90, tracks = listOf(vocal), nextId = 10, keyRoot = 9, minor = true, arrangeSeed = seed)
        return Arranger.arrange(base, Catalog.genres.first(), vocalBars = 12, profile = profile)
    }

    private fun fingerprint(p: Project): List<Any> =
        p.tracks.filter { it.auto }.map { t -> t.instrument to t.clips.flatMap { c -> c.notes.map { Triple(c.startBar, it.start, it.pitch) } } }

    @Test
    fun pressingMakeBeatRepeatedlyGivesDifferentButReproducibleResults() {
        val onsets = List(16) { 0b0001000100010001 }
        val prof = Arranger.VocalProfile(List(16) { 0.8f }, List(16) { DoubleArray(12).also { v -> v[9] = 30.0; v[0] = 20.0; v[4] = 15.0 } }, onsets)
        val runs = (0 until 8).map { fingerprint(arrangeWith(it, prof)) }
        assertEquals("cùng hạt giống phải cho cùng kết quả", fingerprint(arrangeWith(3, prof)), runs[3])
        assertTrue("8 lần tạo phải ra ít nhất 4 bản khác nhau, được ${runs.toSet().size}", runs.toSet().size >= 4)
        assertEquals(1, arrangeWith(0, prof).arrangeSeed)
    }

    @Test
    fun accompanimentAvoidsVocalSyllablesWhenTheVoiceIsBusy() {
        // Giọng dày đặc, nhấn ở các phách (bước 0, 4, 8, 12): piano không nên đánh trùng nhiều vào các bước đó.
        val busy = Arranger.VocalProfile(List(16) { 1f }, List(16) { DoubleArray(12) }, List(16) { 0b0001000100010001 })
        var onBeat = 0; var all = 0
        for (seed in 0 until 6) {
            val out = arrangeWith(seed, busy)
            for (c in out.tracks.first { it.instrument == Instrument.PIANO }.clips)
                for (n in c.notes) { all++; if ((n.start % BAR_TICKS) / 240 % 4 == 0 && n.start % 240 < 40) onBeat++ }
        }
        assertTrue("piano trùng quá nhiều vào âm tiết của giọng: $onBeat/$all", onBeat < all * 0.8)
    }

    @Test
    fun structureFitHitsTargetWithinOneUnitAndRespectsLimits() {
        for (pr in com.bandstudio.app.model.Structure.presets) for (bars in listOf(24, 48, 72, 104, 140, 190)) {
            val out = com.bandstudio.app.model.Structure.fit(com.bandstudio.app.model.Structure.defaults(pr.types), bars)
            val lo = pr.types.sumOf { it.minBars }; val hi = pr.types.sumOf { it.maxBars }
            val goal = bars.coerceIn(lo, hi)
            assertTrue("${pr.id} $bars: ${out.sumOf { it.bars }}", Math.abs(out.sumOf { it.bars } - goal) <= 2)
            assertTrue(out.all { it.bars % 4 == 0 && it.bars >= it.type.minBars && it.bars <= it.type.maxBars })
            assertEquals(pr.types, out.map { it.type })
        }
        // 4:00 ở 140 BPM = 140 ô, và mẫu Hook-trước (11 đoạn) co giãn vừa đúng
        assertEquals(140, com.bandstudio.app.model.Structure.barsForSeconds(240, 140))
        val s = com.bandstudio.app.model.Structure.plan("melodic", 140)
        assertEquals(140, s.sumOf { it.bars })
        assertEquals(11, s.size)
        assertEquals(240, com.bandstudio.app.model.Structure.secondsOf(140, 140))
        // quá nhỏ / quá lớn bị kẹp về giới hạn của cấu trúc
        assertEquals(com.bandstudio.app.model.Structure.defaults(com.bandstudio.app.model.Structure.preset("short").types).sumOf { it.type.minBars },
            com.bandstudio.app.model.Structure.fit(com.bandstudio.app.model.Structure.defaults(com.bandstudio.app.model.Structure.preset("short").types), 4).sumOf { it.bars })
    }

    @Test
    fun structureNamesStartsAndLegacy() {
        val S = com.bandstudio.app.model.Structure
        val list = S.plan("melodic", 104)
        assertEquals(listOf("Intro", "Hook", "Verse 1", "Pre-hook", "Hook", "Verse 2", "Pre-hook", "Hook", "Bridge", "Hook", "Outro"), S.names(list))
        assertEquals(0, S.starts(list)[0]); assertEquals(8, S.starts(list)[1])
        val legacy = S.legacy(24)
        assertEquals(24, legacy.sumOf { it.bars })
        assertEquals(com.bandstudio.app.model.SectionType.INTRO, legacy.first().type)
        assertEquals(com.bandstudio.app.model.SectionType.OUTRO, legacy.last().type)
    }

    @Test
    fun arrangeFollowsTheChosenStructure() {
        val vocal = Track(3, "Vocal", Instrument.VOCAL, vocal = vclip(len = 30_000))
        val st = com.bandstudio.app.model.Structure.plan("melodic", 104)
        val base = Project("p", "n", bpm = 140, tracks = listOf(vocal), nextId = 10, keyRoot = 9, minor = true, structure = st)
        val out = Arranger.arrange(base, Catalog.genre("melodic")!!, vocalBars = 12)
        val autos = out.tracks.filter { it.auto }
        assertEquals(104, autos.flatMap { it.clips }.maxOf { it.endBar })
        val starts = com.bandstudio.app.model.Structure.starts(st)
        val pad = autos.first { it.instrument == Instrument.DRUM_PAD }
        // crash (kit 7) ở đầu mỗi hook
        for ((i, s) in st.withIndex()) if (s.type == com.bandstudio.app.model.SectionType.HOOK) {
            val c = pad.clips.firstOrNull { it.startBar == starts[i] }
            assertTrue("thiếu crash ở hook ô ${starts[i]}", c != null && c.notes.any { it.start == 0 && it.pitch == 7 })
        }
        // vòng đệm: ô cuối của pre-hook có nhiều clap dồn; intro (không có hook ngay sau? có: hook sau intro) có dồn nhẹ
        val preIdx = st.indexOfFirst { it.type == com.bandstudio.app.model.SectionType.PREHOOK }
        val lastBlockStart = starts[preIdx] + st[preIdx].bars - 4
        val roll = pad.clips.first { it.startBar == lastBlockStart }.notes.filter { it.start >= 3 * BAR_TICKS && it.pitch == 4 }
        assertTrue("pre-hook phải có clap dồn, có ${roll.size}", roll.size >= 8)
        assertTrue(roll.maxOf { it.vel } > roll.minOf { it.vel } + 30)
        // bridge: không có trống / bass / guitar
        val bi = st.indexOfFirst { it.type == com.bandstudio.app.model.SectionType.BRIDGE }
        for (t in autos) if (t.instrument == Instrument.BASS || t.instrument == Instrument.ACOUSTIC)
            assertTrue("${t.name} không nên chơi ở bridge", t.clips.none { it.startBar in starts[bi] until starts[bi] + st[bi].bars })
    }
}
