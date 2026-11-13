package com.parkingroom.app.voice

/**
 * 음성 인식 결과에서 숫자를 뽑는다.
 * "12345", "만 이천삼백사십오", "일이삼사오"(한 자리씩 읽기), "일곱"·"넷"(고유어) 모두 후보로 만든다.
 */
object KoreanNumber {

    private val sinoDigit = mapOf('영' to 0, '공' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '륙' to 6, '칠' to 7, '팔' to 8, '구' to 9)
    private val smallUnit = mapOf('십' to 10, '백' to 100, '천' to 1000)
    private val bigUnit = mapOf('만' to 10_000L, '억' to 100_000_000L)
    private val native = listOf(
        "열" to 10, "아홉" to 9, "여덟" to 8, "일곱" to 7, "여섯" to 6, "다섯" to 5,
        "넷" to 4, "네" to 4, "셋" to 3, "세" to 3, "둘" to 2, "두" to 2, "하나" to 1, "한" to 1,
    )

    /** 가능한 숫자 해석을 모두 돌려준다 (문자열, 앞자리 0 보존) */
    fun candidates(text: String): Set<String> {
        val t = text.replace(Regex("(킬로미터|키로|킬로|km|KM|Km|미터|개|살|번|이에요|예요|입니다|야|요)"), "")
            .replace(Regex("[\\s,.!?~]"), "")
        val out = mutableSetOf<String>()
        val digits = t.filter { it.isDigit() }
        if (digits.isNotEmpty()) out += digits
        // 한 자리씩 읽기: "오사삼이일" → "54321"
        val korDigits = t.filter { it in sinoDigit }
        if (korDigits.isNotEmpty() && korDigits.length == t.count { it in sinoDigit || it in smallUnit || it in bigUnit }) {
            out += korDigits.map { sinoDigit.getValue(it) }.joinToString("")
        }
        sinoValue(t)?.let { out += it.toString() }
        native.firstOrNull { (w, _) -> w in t }?.let { out += it.second.toString() }
        return out
    }

    /** "만이천삼백사십오" → 12345 */
    private fun sinoValue(t: String): Long? {
        val s = t.filter { it in sinoDigit || it in smallUnit || it in bigUnit }
        if (s.isEmpty() || s.none { it in smallUnit || it in bigUnit }) return null
        var total = 0L
        var section = 0L
        var digit = -1L
        for (ch in s) {
            when (ch) {
                in sinoDigit -> digit = sinoDigit.getValue(ch).toLong()
                in smallUnit -> {
                    section += (if (digit < 0) 1 else digit) * smallUnit.getValue(ch)
                    digit = -1
                }
                in bigUnit -> {
                    section += maxOf(digit, 0)
                    total += (if (section == 0L) 1 else section) * bigUnit.getValue(ch)
                    section = 0
                    digit = -1
                }
            }
        }
        return total + section + maxOf(digit, 0)
    }
}
