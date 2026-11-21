package com.bandstudio.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun Project.toJson(): JSONObject = JSONObject().apply {
    put("v", 1)
    put("id", id)
    put("name", name)
    put("bpm", bpm)
    put("nextId", nextId)
    put("updatedAt", updatedAt)
    put("key", keyRoot)
    put("minor", minor)
    put("arrGenre", arrangeGenre)
    put("follow", followMelody)
    put("arrSeed", arrangeSeed)
    put("targetSec", targetSeconds)
    put("preset", structurePreset)
    put("struct", JSONArray().also { a -> for (x in structure) a.put(JSONArray().put(x.type.name).put(x.bars)) })
    put("sources", JSONArray().also { a ->
        for (x in sources) a.put(JSONObject().apply {
            put("id", x.id); put("name", x.name); put("file", x.file); put("rate", x.sampleRate)
            put("len", x.lengthMs); put("bpm", x.srcBpm); put("slot", x.slot)
        })
    })
    put("tracks", JSONArray().also { arr ->
        for (t in tracks) arr.put(JSONObject().apply {
            put("id", t.id)
            put("name", t.name)
            put("inst", t.instrument.id)
            put("vol", t.volume.toDouble())
            put("pan", t.pan.toDouble())
            put("mute", t.mute)
            put("solo", t.solo)
            if (t.auto) put("auto", true)
            t.vocal?.let { v ->
                put("vocal", JSONObject().apply {
                    put("raw", v.rawFile); put("file", v.file); put("rate", v.sampleRate); put("len", v.lengthMs)
                    put("sync", v.syncMs); put("nudge", v.nudgeMs)
                    put("denoise", v.denoise); put("enhance", v.enhance); put("tune", v.tune)
                    put("tuneStr", v.tuneStrength.toDouble()); put("tuneSpd", v.tuneSpeed.toDouble()); put("chroma", v.tuneChromatic)
                    put("reverb", v.reverb.toDouble()); put("comp", v.comp.toDouble()); put("bright", v.bright.toDouble()); put("gain", v.gain.toDouble())
                    if (v.segs.isNotEmpty()) put("segs", JSONArray().also { a -> for (g in v.segs) a.put(JSONArray().put(g.id).put(g.srcStartMs).put(g.lenMs).put(g.atMs)) })
                })
            }
            if (t.audio.isNotEmpty()) put("audio", JSONArray().also { a ->
                for (c in t.audio) a.put(JSONObject().apply {
                    put("id", c.id); put("src", c.sourceId); put("bar", c.startBar)
                    put("from", c.srcStartMs); put("len", c.srcLenMs); put("gain", c.gain.toDouble()); put("label", c.label)
                })
            })
            if (t.instrument.isLooper) {
                put("genre", t.genreId)
                put("key", t.keyRoot)
                put("kit", JSONArray().also { ka -> for (pad in t.kit) ka.put(JSONArray().put(pad.soundId).put(pad.semitones)) })
            }
            put("clips", JSONArray().also { cs ->
                for (c in t.clips) cs.put(JSONObject().apply {
                    put("id", c.id)
                    put("startBar", c.startBar)
                    put("bars", c.bars)
                    put("notes", JSONArray().also { ns ->
                        for (n in c.notes) ns.put(JSONArray().put(n.start).put(n.dur).put(n.pitch).put(n.vel))
                    })
                })
            })
        })
    })
}

fun projectFromJson(o: JSONObject): Project {
    val tracks = ArrayList<Track>()
    val ta = o.optJSONArray("tracks") ?: JSONArray()
    for (i in 0 until ta.length()) {
        val t = ta.getJSONObject(i)
        val clips = ArrayList<Clip>()
        val ca = t.optJSONArray("clips") ?: JSONArray()
        for (j in 0 until ca.length()) {
            val c = ca.getJSONObject(j)
            val notes = ArrayList<NoteEvent>()
            val na = c.optJSONArray("notes") ?: JSONArray()
            for (k in 0 until na.length()) {
                val n = na.getJSONArray(k)
                notes.add(NoteEvent(n.getInt(0), n.getInt(1), n.getInt(2), n.optInt(3, 100)))
            }
            clips.add(Clip(c.getInt("id"), c.getInt("startBar"), c.getInt("bars").coerceIn(1, MAX_CLIP_BARS), notes))
        }
        val kit = ArrayList<PadSpec>()
        val ka = t.optJSONArray("kit") ?: JSONArray()
        for (j in 0 until minOf(ka.length(), MAX_KIT)) {
            val e = ka.optJSONArray(j) ?: continue
            kit.add(PadSpec(e.optString(0, ""), e.optInt(1, 0)))
        }
        val vo = t.optJSONObject("vocal")
        val vocal = vo?.let {
            VocalClip(
                rawFile = it.optString("raw", ""), file = it.optString("file", ""),
                sampleRate = it.optInt("rate", 44100).coerceIn(8000, 192000), lengthMs = it.optInt("len", 0).coerceAtLeast(0),
                syncMs = it.optInt("sync", 0), nudgeMs = it.optInt("nudge", 0).coerceIn(-2000, 2000),
                denoise = it.optBoolean("denoise", true), enhance = it.optBoolean("enhance", true), tune = it.optBoolean("tune", false),
                tuneStrength = it.optDouble("tuneStr", 0.85).toFloat().coerceIn(0f, 1f),
                tuneSpeed = it.optDouble("tuneSpd", 0.6).toFloat().coerceIn(0f, 1f), tuneChromatic = it.optBoolean("chroma", false),
                reverb = it.optDouble("reverb", 0.22).toFloat().coerceIn(0f, 1f), comp = it.optDouble("comp", 0.6).toFloat().coerceIn(0f, 1f),
                bright = it.optDouble("bright", 0.4).toFloat().coerceIn(0f, 1f), gain = it.optDouble("gain", 1.0).toFloat().coerceIn(0.1f, 1.5f),
                segs = (it.optJSONArray("segs") ?: JSONArray()).let { a ->
                    (0 until minOf(a.length(), MAX_AUDIO_CLIPS)).mapNotNull { k ->
                        a.optJSONArray(k)?.let { e -> VocalSeg(e.getInt(0), e.getInt(1).coerceAtLeast(0), e.getInt(2).coerceAtLeast(0), e.getInt(3).coerceAtLeast(0)) }
                    }
                },
            )
        }
        val audio = ArrayList<AudioClip>()
        val aa = t.optJSONArray("audio") ?: JSONArray()
        for (j in 0 until minOf(aa.length(), MAX_AUDIO_CLIPS)) {
            val c = aa.getJSONObject(j)
            audio.add(AudioClip(
                c.getInt("id"), c.getInt("src"), c.optInt("bar", 0).coerceIn(0, MAX_BARS - 1),
                c.optInt("from", 0).coerceAtLeast(0), c.optInt("len", 0).coerceAtLeast(0),
                c.optDouble("gain", 1.0).toFloat().coerceIn(0.1f, 1.5f), c.optString("label", ""),
            ))
        }
        tracks.add(
            Track(
                id = t.getInt("id"),
                name = t.optString("name", "Track"),
                instrument = Instrument.fromId(t.optInt("inst", 0)),
                volume = t.optDouble("vol", 0.8).toFloat().coerceIn(0f, 1.2f),
                pan = t.optDouble("pan", 0.0).toFloat().coerceIn(-1f, 1f),
                mute = t.optBoolean("mute", false),
                solo = t.optBoolean("solo", false),
                clips = clips,
                genreId = t.optString("genre", ""),
                kit = kit,
                keyRoot = Math.floorMod(t.optInt("key", DEFAULT_KEY), 12),
                vocal = vocal,
                audio = audio,
                auto = t.optBoolean("auto", false),
            ),
        )
    }
    val sources = ArrayList<AudioSource>()
    val sa = o.optJSONArray("sources") ?: JSONArray()
    for (i in 0 until sa.length()) {
        val x = sa.getJSONObject(i)
        sources.add(AudioSource(
            x.getInt("id"), x.optString("name", "Audio"), x.optString("file", ""),
            x.optInt("rate", 44100).coerceIn(8000, 192000), x.optInt("len", 0).coerceAtLeast(0),
            x.optInt("bpm", 0).coerceIn(0, 240), x.optInt("slot", FIRST_AUDIO_SLOT).coerceIn(FIRST_AUDIO_SLOT, LAST_AUDIO_SLOT),
        ))
    }
    return Project(
        sources = sources,
        id = o.getString("id"),
        name = o.optString("name", "Bản nhạc"),
        bpm = o.optInt("bpm", 100).coerceIn(40, 240),
        tracks = tracks.take(MAX_TRACKS),
        nextId = o.optInt("nextId", 1),
        updatedAt = o.optLong("updatedAt", 0L),
        keyRoot = Math.floorMod(o.optInt("key", DEFAULT_KEY), 12),
        minor = o.optBoolean("minor", false),
        arrangeGenre = o.optString("arrGenre", "hiphop"),
        followMelody = o.optBoolean("follow", false),
        arrangeSeed = o.optInt("arrSeed", 0),
        targetSeconds = o.optInt("targetSec", 0).coerceIn(0, 3600),
        structurePreset = o.optString("preset", "standard"),
        structure = (o.optJSONArray("struct") ?: JSONArray()).let { a ->
            (0 until minOf(a.length(), 64)).mapNotNull { i ->
                val e = a.optJSONArray(i) ?: return@mapNotNull null
                val t = Structure.typeOf(e.optString(0)) ?: return@mapNotNull null
                SongSection(t, e.optInt(1, t.defaultBars).coerceIn(Structure.UNIT, MAX_BARS))
            }
        },
    )
}

/** Lưu dự án thành từng tệp JSON trong bộ nhớ riêng của ứng dụng. Không cần mạng, không cần tài khoản. */
class ProjectStore(private val dir: File) {

    private fun fileOf(id: String) = File(dir, id.filter { it.isLetterOrDigit() || it == '-' } + ".json")

    fun list(): List<Project> {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                projectFromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                null // tệp hỏng: bỏ qua, không làm sập ứng dụng
            }
        }.sortedByDescending { it.updatedAt }
    }

    fun save(p: Project) {
        dir.mkdirs()
        val target = fileOf(p.id)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(p.toJson().toString())
        if (!tmp.renameTo(target)) { // ghi tạm rồi đổi tên để không mất dữ liệu nếu bị ngắt giữa chừng
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete(id: String) {
        fileOf(id).delete()
    }
}
