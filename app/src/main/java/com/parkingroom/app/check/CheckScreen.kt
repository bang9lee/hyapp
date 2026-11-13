package com.parkingroom.app.check

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 1단계 차량 기능 확인 화면: 왼쪽 버튼, 가운데 항목별 마지막 결과, 오른쪽 실시간 로그 */
@Composable
fun CheckScreen(onCommand: (String) -> Unit) {
    val commands = listOf(
        "all" to "전체 자동 확인",
        "caps" to "① 지원 여부",
        "read" to "② 현재 값 읽기",
        "listen" to "③ 변화 구독",
        "write" to "④ 쓰기 테스트",
        "tts" to "음성 합성",
        "stt" to "음성 인식 시작",
        "sttStop" to "음성 인식 정지",
        "stereo" to "좌/우 스피커",
        "loc" to "위치·동네 이름",
    )
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("차량 기능 확인", fontSize = 22.sp)
            commands.forEach { (cmd, label) ->
                Button(onClick = { onCommand(cmd) }, modifier = Modifier.fillMaxWidth()) { Text(label, fontSize = 16.sp) }
            }
        }
        val summary = CheckLog.summary.entries.sortedBy { it.key }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().background(Color(0xFF15171C)).padding(8.dp),
        ) {
            items(summary, key = { it.key }) { (item, last) ->
                Text("$item  →  $last", fontSize = 14.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().background(Color(0xFF0E1013)).padding(8.dp),
        ) {
            items(CheckLog.recent) { line ->
                Text(line, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
