package com.bandstudio.app.model

import org.json.JSONObject

/** Một vùng phát của nhạc cụ có cao độ: mẫu gốc ở nốt [root], dùng cho nốt [lo]..[hi] và velocity [velLo]..[velHi]. */
data class InstrumentZone(val file: String, val root: Int, val lo: Int, val hi: Int, val velLo: Int, val velHi: Int)

/** Bộ mẫu của một nhạc cụ trong engine ([inst] khớp enum Inst của C++: 0 piano, 1 acoustic, 2 electric, 3 bass). */
data class InstrumentSet(val inst: Int, val name: String, val gain: Float, val zones: List<InstrumentZone>)

/** assets/instruments/manifest.json: danh sách vùng phát của piano, guitar, bass (mẫu CC0 / public domain, xem SOURCES.md). */
object Instruments {
    /** Các ô mẫu của engine từ đây trở lên dành cho nhạc cụ (ô thấp hơn là âm trống/percussion của Looper). */
    const val FIRST_SLOT = 100

    fun parse(json: String): List<InstrumentSet> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("instruments")
        return List(arr.length()) { i ->
            val e = arr.getJSONObject(i)
            val za = e.getJSONArray("zones")
            InstrumentSet(
                inst = e.getInt("inst"),
                name = e.getString("name"),
                gain = e.getDouble("gain").toFloat(),
                zones = List(za.length()) { j ->
                    val z = za.getJSONObject(j)
                    InstrumentZone(z.getString("file"), z.getInt("root"), z.getInt("lo"), z.getInt("hi"), z.getInt("velLo"), z.getInt("velHi"))
                },
            )
        }
    }
}
