package com.example.myplan.travel.live

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myplan.ui.component.CompanionNote
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class EvidenceTraffic(val day: Int, val from: String, val to: String, val minutes: Int, val checkedAt: String, val mode: String)
private data class EvidenceArchive(val sources: List<TravelSource>, val warnings: List<String>, val traffic: List<EvidenceTraffic>, val generatedAt: String)

/** Generation evidence is an archive: editing the saved stops does not reverify it. */
@Composable
fun TravelEvidenceSection(evidenceJson: String?) {
    if (evidenceJson.isNullOrBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val archive = remember(evidenceJson) { readEvidence(evidenceJson) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (expanded) "收起生成时的来源与提醒" else "查看生成时的来源与提醒", Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (expanded) {
            Text("生成时的攻略记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            CompanionNote("这是首次 AI 生成时保存的记录。手动修改节点、日期或顺序不会更新这些资料；原交通估计与地点核验不能视为修改后行程的核验结果。")
            if (archive == null) {
                Text("原记录暂时无法读取；已保存的行程节点仍可继续查看和编辑。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (archive.generatedAt.isNotBlank()) Text("原行程生成于 ${archive.generatedAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (archive.warnings.isNotEmpty()) {
                    Text("当时返回的提醒", style = MaterialTheme.typography.titleSmall)
                    archive.warnings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                Text("攻略出处", style = MaterialTheme.typography.titleSmall)
                if (archive.sources.isEmpty()) Text("原记录没有可展示的攻略出处。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                archive.sources.forEach { source ->
                    OutlinedButton(onClick = {
                        val safe = TravelLiveJson.sourceUrl(source.url)
                        if (safe.isBlank()) linkError = "这篇攻略没有可打开的原帖链接。"
                        else try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))); linkError = null }
                        catch (_: Exception) { linkError = "暂时无法打开原帖，请稍后再试。" }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(source.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (source.author.isNotBlank()) Text(source.author, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    }
                }
                linkError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text("原交通查询", style = MaterialTheme.typography.titleSmall)
                if (archive.traffic.isEmpty()) {
                    Text("原记录没有已查询的交通路段。预留间隔不代表实际耗时。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else archive.traffic.forEach { leg ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("第 ${leg.day} 天 · ${leg.from} → ${leg.to}", style = MaterialTheme.typography.bodyMedium)
                        Text("${leg.mode.ifBlank { "交通" }}估计 ${leg.minutes} 分钟${leg.checkedAt.let { if (it.isBlank()) "" else " · 查询于 $it" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("仅为生成时查询的耗时估计，不保证出行当天交通；营业、预约和票务仍需出行前核对。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun readEvidence(value: String): EvidenceArchive? = runCatching {
    require(value.length <= 2 * 1024 * 1024)
    val result = JSONObject(value)
    val trip = result.getJSONObject("trip")
    val sources = TravelLiveJson.sources(trip.optJSONArray("sources"))
    val warnings = result.optJSONArray("warnings")?.strings().orEmpty().filter { it.isNotBlank() }
    val traffic = mutableListOf<EvidenceTraffic>()
    val days = trip.getJSONArray("days").objects()
    require(days.size in 1..5)
    days.forEach { day ->
        val stops = day.getJSONArray("stops").objects().associateBy { it.getString("id") }
        day.optJSONArray("legs")?.objects().orEmpty().forEach { leg ->
            val from = stops[leg.optString("fromCandidateId")]
            val to = stops[leg.optString("toCandidateId")]
            val minutes = leg.optInt("durationMinutes")
            if (from != null && to != null && leg.optString("status") == "estimated" && minutes in 1..1440) {
                traffic += EvidenceTraffic(day.getInt("day"), from.getString("title"), to.getString("title"), minutes,
                    evidenceTime(leg.optString("checkedAt")), leg.optString("mode"))
            }
        }
    }
    EvidenceArchive(sources, warnings, traffic, evidenceTime(trip.optString("createdAt")))
}.getOrNull()

private fun evidenceTime(value: String): String = try {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))
} catch (_: Exception) { "" }
