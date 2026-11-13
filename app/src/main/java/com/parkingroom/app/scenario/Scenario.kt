package com.parkingroom.app.scenario

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 시나리오 파일(assets/scenarios 폴더의 json) 모델.
 * 새 이야기는 JSON만 추가하면 된다 — 대사·정답 조건(puzzle)·연출 순서(scene)를 모두 파일에서 읽는다.
 *
 * 난이도별 값은 {"default": .., "normal": .., "hard": .., "glitch": ..} 형태로 쓰고,
 * 없으면 glitch → hard → normal → easy → default 순서로 찾는다.
 */
enum class GameMode { ADULT, FAMILY }

data class Level(
    val key: String,
    val label: String,
    val hint: HintMode,
    val hintAfterSec: Int,
    val maxHints: Int,
    val timeLimitSec: Int,
    val fake: Boolean,
    val glitch: Boolean,
    val unlockAfter: String?,
    val warning: String?,
    val description: String,
)

enum class HintMode { AUTO, ASK, NONE }

/** 난이도별로 다른 값 */
class Variants<T>(private val byKey: Map<String, T>) {
    fun get(level: String): T? = chain(level).firstNotNullOfOrNull { byKey[it] }
    fun isEmpty() = byKey.isEmpty()

    companion object {
        fun chain(level: String): List<String> = when (level) {
            "glitch" -> listOf("glitch", "hard", "normal", "easy", "default")
            "hard" -> listOf("hard", "normal", "easy", "default")
            "normal" -> listOf("normal", "easy", "default")
            "old" -> listOf("old", "young", "default")
            else -> listOf(level, "default")
        }
    }
}

data class Action(val at: Long, val op: String, val args: JSONObject)

data class Scene(val durationMs: Long, val actions: List<Action>)

data class Piece(
    val id: String,
    val title: String,
    val subtitle: String,
    val color: Int,
    val only: Set<String>,
    val riddle: Variants<String>,
    val hints: Variants<List<String>>,
    val puzzle: Variants<JSONObject>,
    val scene: Variants<Scene>,
    val line: Variants<String>,
    val reward: Variants<String>,
    val alt: Piece?,
) {
    fun availableFor(level: String) = only.isEmpty() || level in only
}

data class Scenario(
    val id: String,
    val mode: GameMode,
    val title: String,
    val subtitle: String,
    val levels: List<Level>,
    val intro: Variants<String>,
    val introScene: Variants<Scene>,
    val pieces: List<Piece>,
    val fake: Piece?,
    val final: Piece?,
    val endingScene: Variants<Scene>,
    val endingLine: Variants<String>,
    val texts: Map<String, String>,
    /** 인트로 뒤 보안팀 무전 */
    val introTeam: Variants<String>,
    /** NULL의 기습(무작위로 하나씩) */
    val attacks: List<Scene>,
    /** 틀렸을 때 NULL의 반격 */
    val counterScene: Variants<Scene>,
    /** 시간 초과 엔딩 */
    val failScene: Variants<Scene>,
    val failLine: Variants<String>,
) {
    fun level(key: String) = levels.first { it.key == key }
    fun text(key: String, vararg args: Pair<String, Any?>): String =
        args.fold(texts[key].orEmpty()) { s, (k, v) -> s.replace("{$k}", v?.toString().orEmpty()) }

    companion object {
        fun load(context: Context, file: String): Scenario =
            context.assets.open("scenarios/$file").bufferedReader().use { parse(JSONObject(it.readText())) }

        fun parse(o: JSONObject) = Scenario(
            id = o.getString("id"),
            mode = if (o.optString("mode") == "family") GameMode.FAMILY else GameMode.ADULT,
            title = o.getString("title"),
            subtitle = o.optString("subtitle"),
            levels = o.getJSONArray("levels").objects().map(::parseLevel),
            intro = strings(o.opt("intro")),
            introScene = scenes(o.opt("introScene")),
            pieces = o.optJSONArray("pieces").objects().map(::parsePiece),
            fake = o.optJSONObject("fake")?.let(::parsePiece),
            final = o.optJSONObject("final")?.let(::parsePiece),
            endingScene = scenes(o.opt("endingScene")),
            endingLine = strings(o.opt("endingLine")),
            texts = o.optJSONObject("texts")?.let { t -> t.keys().asSequence().associateWith { t.getString(it) } }.orEmpty(),
            introTeam = strings(o.opt("introTeam")),
            attacks = o.optJSONArray("attacks").objects().map(::parseScene),
            counterScene = scenes(o.opt("counterScene")),
            failScene = scenes(o.opt("failScene")),
            failLine = strings(o.opt("failLine")),
        )

        private fun parseLevel(o: JSONObject) = Level(
            key = o.getString("key"),
            label = o.getString("label"),
            hint = when (o.optString("hint")) {
                "auto" -> HintMode.AUTO
                "ask" -> HintMode.ASK
                else -> HintMode.NONE
            },
            hintAfterSec = o.optInt("hintAfterSec", 60),
            maxHints = o.optInt("maxHints", 0),
            timeLimitSec = o.optInt("timeLimitSec", 0),
            fake = o.optBoolean("fake"),
            glitch = o.optBoolean("glitch"),
            unlockAfter = o.optString("unlockAfter").ifBlank { null },
            warning = o.optString("warning").ifBlank { null },
            description = o.optString("description"),
        )

        private fun parsePiece(o: JSONObject): Piece = Piece(
            id = o.getString("id"),
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            color = parseColor(o.optString("color", "#FFFFFF")),
            only = o.optJSONArray("only")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
            riddle = strings(o.opt("riddle")),
            hints = variants(o.opt("hints")) { v -> (v as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) } } },
            puzzle = puzzles(o.opt("puzzle")),
            scene = scenes(o.opt("scene")),
            line = strings(o.opt("line")),
            reward = strings(o.opt("reward")),
            alt = o.optJSONObject("alt")?.let(::parsePiece),
        )

        /** puzzle: {"type":..., "default":{..}, "hard":{..}} → 난이도별로 default와 합친 JSON */
        private fun puzzles(v: Any?): Variants<JSONObject> {
            val o = v as? JSONObject ?: return Variants(emptyMap())
            val type = o.optString("type")
            val base = o.optJSONObject("default") ?: JSONObject()
            val keys = o.keys().asSequence().filter { it != "type" && it != "default" }.toList()
            val map = mutableMapOf("default" to merged(type, base, null))
            for (k in keys) o.optJSONObject(k)?.let { map[k] = merged(type, base, it) }
            return Variants(map)
        }

        private fun merged(type: String, base: JSONObject, over: JSONObject?): JSONObject {
            val r = JSONObject(base.toString())
            over?.keys()?.forEach { r.put(it, over.get(it)) }
            if (!r.has("type")) r.put("type", type)
            return r
        }

        private fun scenes(v: Any?): Variants<Scene> {
            val o = v as? JSONObject ?: return Variants(emptyMap())
            // 단일 scene({"durationMs":..}) 또는 난이도별 scene
            if (o.has("actions")) return Variants(mapOf("default" to parseScene(o)))
            return Variants(o.keys().asSequence().associateWith { parseScene(o.getJSONObject(it)) })
        }

        private fun parseScene(o: JSONObject) = Scene(
            durationMs = o.optLong("durationMs", 0),
            actions = o.optJSONArray("actions").objects()
                .map { Action(it.optLong("at"), it.getString("do"), it) }
                .sortedBy { it.at },
        )

        private fun strings(v: Any?): Variants<String> = variants(v) { it as? String }

        private fun <T> variants(v: Any?, conv: (Any) -> T?): Variants<T> = when (v) {
            null, JSONObject.NULL -> Variants(emptyMap())
            is JSONObject -> {
                // 값이 JSONObject지만 난이도 키가 아니라 그 자체가 값인 경우는 없다고 가정
                Variants(v.keys().asSequence().mapNotNull { k -> conv(v.get(k))?.let { k to it } }.toMap())
            }
            else -> conv(v)?.let { Variants(mapOf("default" to it)) } ?: Variants(emptyMap())
        }

        fun parseColor(s: String): Int = android.graphics.Color.parseColor(s)

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    }
}
