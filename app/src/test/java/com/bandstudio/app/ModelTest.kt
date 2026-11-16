package com.bandstudio.app

import com.bandstudio.app.model.Clip
import com.bandstudio.app.model.Instrument
import com.bandstudio.app.model.NoteEvent
import com.bandstudio.app.model.Project
import com.bandstudio.app.model.Quantizer
import com.bandstudio.app.model.Track
import com.bandstudio.app.model.encodeForEngine
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
        assertArrayEquals(intArrayOf(1, 0, 0, 7680, 7680, 1, 0, 1, 3840, 480, 60, 100), data)
    }

    @Test
    fun encodeForEngineClipsLongNotesToClipEnd() {
        val clip = Clip(id = 2, startBar = 0, bars = 1, notes = listOf(NoteEvent(3600, 1000, 60)))
        val project = Project("p", "n", tracks = listOf(Track(1, "Bass", Instrument.BASS, clips = listOf(clip))))
        val data = project.encodeForEngine(loop = true, loopStart = 0, loopEnd = 3840, length = 0)
        assertEquals(240, data[9]) // dur bị cắt từ 1000 xuống 240
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
}
