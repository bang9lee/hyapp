package com.parkingroom.app.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 성인 모드 기록 (시간은 벌점 포함) */
data class GameRecord(
    val levelKey: String,
    val levelLabel: String,
    val coop: Boolean,
    val elapsedMs: Long,
    val hintsUsed: Int,
    val rhythmAccuracy: Float?,
    val penalties: Int,
    val atMillis: Long,
) {
    /** 랭킹 시즌 = 월 단위 */
    val season: String get() = SimpleDateFormat("yyyy-MM", Locale.KOREA).format(Date(atMillis))

    fun toJson() = JSONObject()
        .put("level", levelKey).put("label", levelLabel).put("coop", coop).put("ms", elapsedMs)
        .put("hints", hintsUsed).put("rhythm", rhythmAccuracy?.toDouble() ?: JSONObject.NULL)
        .put("penalties", penalties).put("at", atMillis)

    companion object {
        fun from(o: JSONObject) = GameRecord(
            o.getString("level"), o.getString("label"), o.getBoolean("coop"), o.getLong("ms"),
            o.optInt("hints"), if (o.isNull("rhythm")) null else o.getDouble("rhythm").toFloat(),
            o.optInt("penalties"), o.getLong("at"),
        )
    }
}

data class Settings(
    /** 비 오는 날: 첫눈(창문) 기억을 핸들·사이드미러 열선 퍼즐로 바꾼다 */
    val rainyDay: Boolean = false,
    val sfxVolume: Float = 0.9f,
    val kidName: String = "",
)

/** 기기 안에만 저장한다 (서버 없음) */
class RecordStore(context: Context) {
    private val prefs = context.getSharedPreferences("parking_room", Context.MODE_PRIVATE)

    private val _records = MutableStateFlow(loadRecords())
    private val _settings = MutableStateFlow(loadSettings())
    private val _glitchUnlocked = MutableStateFlow(prefs.getBoolean(KEY_GLITCH, false))
    val records: StateFlow<List<GameRecord>> = _records.asStateFlow()
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val glitchUnlocked: StateFlow<Boolean> = _glitchUnlocked.asStateFlow()

    private fun loadRecords(): List<GameRecord> = runCatching {
        val a = JSONArray(prefs.getString(KEY_RECORDS, "[]"))
        (0 until a.length()).map { GameRecord.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun loadSettings() = Settings(
        rainyDay = prefs.getBoolean("rainyDay", false),
        sfxVolume = prefs.getFloat("sfxVolume", 0.9f),
        kidName = prefs.getString("kidName", "").orEmpty(),
    )

    fun add(r: GameRecord) {
        val list = (_records.value + r).sortedBy { it.elapsedMs }.take(200)
        prefs.edit().putString(KEY_RECORDS, JSONArray(list.map { it.toJson() }).toString()).apply()
        _records.value = list
        if (r.levelKey == "hard") unlockGlitch()
    }

    fun unlockGlitch() {
        prefs.edit().putBoolean(KEY_GLITCH, true).apply()
        _glitchUnlocked.value = true
    }

    fun update(s: Settings) {
        prefs.edit().putBoolean("rainyDay", s.rainyDay).putFloat("sfxVolume", s.sfxVolume).putString("kidName", s.kidName).apply()
        _settings.value = s
    }

    /** 순위: 같은 난이도·혼자/협동·시즌끼리 */
    fun ranking(levelKey: String, coop: Boolean, season: String) =
        _records.value.filter { it.levelKey == levelKey && it.coop == coop && it.season == season }.sortedBy { it.elapsedMs }

    fun rankOf(r: GameRecord) = ranking(r.levelKey, r.coop, r.season).indexOfFirst { it.atMillis == r.atMillis } + 1

    companion object {
        private const val KEY_RECORDS = "records"
        private const val KEY_GLITCH = "glitchUnlocked"
    }
}
