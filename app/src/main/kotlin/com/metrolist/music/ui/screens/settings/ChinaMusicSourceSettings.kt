/**
 * Metrolist Project (C) 2026
 * Settings screen for Chinese music source API configuration - multi-source management
 */

package com.metrolist.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.metrolist.chinamusic.ChinaMusicApi
import com.metrolist.chinamusic.DEFAULT_MUSIC_SOURCE
import com.metrolist.chinamusic.MusicSourceConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ActiveChinaMusicSourceIdKey
import com.metrolist.music.constants.ChinaMusicSourcesKey
import com.metrolist.music.ui.component.IconButton as TopIconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChinaMusicSourceSettings(
    navController: NavController,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sources = remember { mutableStateListOf<MusicSourceConfig>() }
    var activeSourceId by remember { mutableStateOf("") }
    var isLoaded by remember { mutableStateOf(false) }

    // Dialog state
    var showAddDialog by remember { mutableStateOf(false) }
    var editSourceId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogMessage by remember { mutableStateOf("") }
    var showAlert by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }

    // Load from DataStore
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        val jsonStr = prefs[ChinaMusicSourcesKey] ?: ""
        val activeId = prefs[ActiveChinaMusicSourceIdKey] ?: ""
        val loadedSources = MusicSourceConfig.listFromJson(jsonStr)
        sources.clear()
        sources.addAll(loadedSources)
        activeSourceId = activeId
        isLoaded = true
    }

    fun saveSources(newSources: List<MusicSourceConfig>, newActiveId: String) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val jsonStr = json.encodeToString(newSources)
                context.dataStore.edit { prefs ->
                    prefs[ChinaMusicSourcesKey] = jsonStr
                    prefs[ActiveChinaMusicSourceIdKey] = newActiveId
                }
            }
            newSources.find { it.id == newActiveId }?.let { ChinaMusicApi.configure(it) }
        }
    }

    // Add dialog state
    var addName by remember { mutableStateOf("") }
    var addUrl by remember { mutableStateOf("") }
    var addKey by remember { mutableStateOf("") }
    var importMode by remember { mutableStateOf("manual") } // manual | url | file
    var importUrl by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }

    // Helper: parse imported content (JSON or JS format) - must be before filePickerLauncher
    fun parseImportContent(content: String): List<MusicSourceConfig> {
        val trimmed = content.trim()

        // Try 1: JS array pattern (JSON array assigned to var/const/let)
        val jsArrayPattern = Regex("""(?:var|const|let)\s+\w+\s*=\s*(\[[\s\S]*?\])\s*;?\s*$""")
        val jsArrayMatch = jsArrayPattern.find(trimmed)
        if (jsArrayMatch != null) {
            val jsonStr = jsArrayMatch.groupValues[1]
            try {
                val element = json.parseToJsonElement(jsonStr)
                if (element is kotlinx.serialization.json.JsonArray) {
                    return element.mapNotNull { item ->
                        val obj = item.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val api = (obj["api"] ?: obj["apiUrl"] ?: obj["url"])?.jsonPrimitive?.content ?: return@mapNotNull null
                        val key = (obj["key"] ?: obj["apiKey"] ?: obj["api_key"])?.jsonPrimitive?.content ?: ""
                        MusicSourceConfig(name = name, apiUrl = api, apiKey = key)
                    }
                }
            } catch (_: Exception) { /* fall through */ }
        }

        // Try 2: Pure JSON (array or object)
        try {
            val element = json.parseToJsonElement(trimmed)
            when {
                element is kotlinx.serialization.json.JsonArray -> {
                    return element.mapNotNull { item ->
                        val obj = item.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val api = (obj["api"] ?: obj["apiUrl"] ?: obj["url"])?.jsonPrimitive?.content ?: return@mapNotNull null
                        val key = (obj["key"] ?: obj["apiKey"] ?: obj["api_key"])?.jsonPrimitive?.content ?: ""
                        MusicSourceConfig(name = name, apiUrl = api, apiKey = key)
                    }
                }
                element is kotlinx.serialization.json.JsonObject -> {
                    val name = element["name"]?.jsonPrimitive?.content ?: return emptyList()
                    val api = (element["api"] ?: element["apiUrl"] ?: element["url"])?.jsonPrimitive?.content ?: return emptyList()
                    val key = (element["key"] ?: element["apiKey"] ?: element["api_key"])?.jsonPrimitive?.content ?: ""
                    return listOf(MusicSourceConfig(name = name, apiUrl = api, apiKey = key))
                }
            }
        } catch (_: Exception) { /* fall through */ }

        // Try 3: LX-Music JS format — extract const API_URL / API_KEY / @name
        val apiUrlPattern = Regex("""(?:const|let|var)\s+API_URL\s*=\s*['"]([^'"]+)['"]""")
        val apiKeyPattern = Regex("""(?:const|let|var)\s+API_KEY\s*=\s*['"]([^'"]+)['"]""")
        val namePattern = Regex("""@name\s+(.+?)(?:\n|\r|$)""")
        val url = apiUrlPattern.find(trimmed)?.groupValues?.get(1) ?: ""
        val key = apiKeyPattern.find(trimmed)?.groupValues?.get(1) ?: ""
        val name = namePattern.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
        if (url.isNotBlank()) {
            return listOf(MusicSourceConfig(
                name = name.ifBlank { "导入的音源" },
                apiUrl = url,
                apiKey = key,
            ))
        }

        return emptyList()
    }

    // Helper: fetch and import from URL
    fun importFromUrl() {
        val url = importUrl.trim()
        if (url.isBlank()) {
            dialogTitle = "输入错误"
            dialogMessage = "请输入有效的 URL"
            showAlert = true
            return
        }
        coroutineScope.launch {
            isImporting = true
            try {
                val content = withContext(Dispatchers.IO) {
                    URL(url).openStream().bufferedReader().use { it.readText() }
                }
                val imported = parseImportContent(content)
                if (imported.isEmpty()) {
                    showAlert = true
                    dialogTitle = "导入失败"
                    dialogMessage = "无法解析链接内容，请确认链接指向有效的音乐源 JSON 文件"
                } else {
                    val updated = sources.toList() + imported
                    saveSources(updated, activeSourceId)
                    sources.clear()
                    sources.addAll(updated)
                    showAddDialog = false
                }
            } catch (e: Exception) {
                showAlert = true
                dialogTitle = "导入失败"
                dialogMessage = "获取链接失败：${e.message}"
            } finally {
                isImporting = false
            }
        }
    }

    // File picker for importing music source JSON/JS files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                isImporting = true
                try {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                            reader.readText()
                        } ?: ""
                    }
                    val imported = parseImportContent(content)
                    if (imported.isEmpty()) {
                        showAlert = true
                        dialogTitle = "导入失败"
                        dialogMessage = "无法解析文件内容，请确认文件格式正确"
                    } else {
                        val updated = sources.toList() + imported
                        saveSources(updated, activeSourceId)
                        sources.clear()
                        sources.addAll(updated)
                        showAddDialog = false
                    }
                } catch (e: Exception) {
                    showAlert = true
                    dialogTitle = "导入失败"
                    dialogMessage = "读取文件失败：${e.message}"
                } finally {
                    isImporting = false
                }
            }
        }
    }

    // Edit dialog state
    var editName by remember { mutableStateOf("") }
    var editUrl by remember { mutableStateOf("") }
    var editKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )
        Spacer(Modifier.height(16.dp))

        // === HELP SECTION ===
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "什么是音乐源？",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "音乐源是提供国内音乐搜索和播放的后端 API 服务。配置音乐源后，即可搜索和收听酷狗、酷我、网易云、QQ音乐、咪咕等平台的音乐。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "如何获取音乐源：",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "1. 自行搭建后端服务\n2. 从社区获取共享源地址\n3. 参考落雪音乐助手的音乐源项目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    buildAnnotatedString {
                        append("示例项目：")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("https://github.com/guoyue2010/lxmusic-/tree/main")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/guoyue2010/lxmusic-/tree/main"))
                        context.startActivity(intent)
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // === SOURCE LIST ===
        if (!isLoaded) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp),
            )
        } else {
            Text(
                text = "已配置的音乐源",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            sources.forEach { source ->
                val isActive = source.id == activeSourceId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = isActive,
                                onClick = {
                                    coroutineScope.launch {
                                        saveSources(sources.toList(), source.id)
                                        activeSourceId = source.id
                                    }
                                },
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(
                                    text = source.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    text = source.apiUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = {
                                    editSourceId = source.id
                                    editName = source.name
                                    editUrl = source.apiUrl
                                    editKey = source.apiKey
                                }) {
                                    Icon(
                                        painterResource(R.drawable.edit),
                                        contentDescription = "编辑",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(onClick = {
                                    deleteConfirmId = source.id
                                }) {
                                    Icon(
                                        painterResource(R.drawable.close),
                                        contentDescription = "删除",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    addName = ""
                    addUrl = ""
                    addKey = ""
                    showAddDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("添加音乐源")
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // === TOP BAR ===
    TopAppBar(
        title = { Text("音乐源设置") },
        navigationIcon = {
            TopIconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )

    // === ADD DIALOG ===
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加音乐源") },
            text = {
                Column {
                    // Tab selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("manual" to "手动输入", "url" to "链接导入", "file" to "文件导入").forEach { (mode, label) ->
                            TextButton(
                                onClick = { importMode = mode },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = label,
                                    color = if (importMode == mode) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (importMode == mode) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Manual input
                    if (importMode == "manual") {
                        OutlinedTextField(
                            value = addName,
                            onValueChange = { addName = it },
                            label = { Text("名称") },
                            placeholder = { Text("例如：我的服务器") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = addUrl,
                            onValueChange = { addUrl = it },
                            label = { Text("API 地址") },
                            placeholder = { Text("https://example.com/api/music") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = addKey,
                            onValueChange = { addKey = it },
                            label = { Text("API 密钥") },
                            placeholder = { Text("输入 API 密钥") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }

                    // URL import
                    if (importMode == "url") {
                        Text(
                            text = "输入音源脚本的链接地址，点击导入即可自动提取 API 地址和密钥。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = { importUrl = it },
                            label = { Text("音源链接") },
                            placeholder = { Text("https://source.shiqianjiang.cn/api/script/lx?key=卡密") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }

                    // File import
                    if (importMode == "file") {
                        Text(
                            text = "选择本地的落雪音乐 JS 音源文件，自动提取 API 地址和密钥。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isImporting,
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("选择文件（.js）")
                        }
                    }

                    if (isImporting) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                if (importMode == "manual") {
                    TextButton(
                        onClick = {
                            val name = addName.trim()
                            val url = addUrl.trim()
                            val key = addKey.trim()
                            if (name.isBlank() || url.isBlank() || key.isBlank()) {
                                dialogTitle = "输入错误"
                                dialogMessage = "名称、地址和密钥不能为空"
                                showAlert = true
                                return@TextButton
                            }
                            val newSource = MusicSourceConfig(name = name, apiUrl = url, apiKey = key)
                            val updatedList = sources.toList() + newSource
                            coroutineScope.launch {
                                saveSources(updatedList, activeSourceId)
                                sources.clear()
                                sources.addAll(updatedList)
                            }
                            showAddDialog = false
                        },
                    ) {
                        Text("添加")
                    }
                } else if (importMode == "url") {
                    TextButton(
                        onClick = { importFromUrl() },
                        enabled = !isImporting,
                    ) {
                        Text("导入")
                    }
                }
                // file mode: no confirm button, picker handles it
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // === EDIT DIALOG ===
    if (editSourceId != null) {
        val source = sources.find { it.id == editSourceId }
        AlertDialog(
            onDismissRequest = { editSourceId = null },
            title = { Text("编辑音乐源") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("API 地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editKey,
                        onValueChange = { editKey = it },
                        label = { Text("API 密钥（留空则不修改）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sid = editSourceId ?: return@TextButton
                        val name = editName.trim()
                        val url = editUrl.trim()
                        if (name.isBlank() || url.isBlank()) {
                            dialogTitle = "输入错误"
                            dialogMessage = "名称和地址不能为空"
                            showAlert = true
                            return@TextButton
                        }
                        val updatedKey = editKey.trim().ifBlank { source?.apiKey ?: "" }
                        val updated = sources.toMutableList().map {
                            if (it.id == sid) it.copy(name = name, apiUrl = url, apiKey = updatedKey)
                            else it
                        }
                        coroutineScope.launch {
                            saveSources(updated, activeSourceId)
                            sources.clear()
                            sources.addAll(updated)
                        }
                        editSourceId = null
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editSourceId = null }) {
                    Text("取消")
                }
            },
        )
    }

    // === DELETE CONFIRMATION ===
    if (deleteConfirmId != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("删除音乐源") },
            text = { Text("确定要删除这个音乐源吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleteConfirmId ?: return@TextButton
                        val updated = sources.filter { it.id != id }
                        val newActive = if (id == activeSourceId) updated.firstOrNull()?.id ?: ""
                        else activeSourceId
                        coroutineScope.launch {
                            saveSources(updated, newActive)
                            sources.clear()
                            sources.addAll(updated)
                            activeSourceId = newActive
                        }
                        deleteConfirmId = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) {
                    Text("取消")
                }
            },
        )
    }

    // === ALERT DIALOG ===
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(dialogTitle) },
            text = { Text(dialogMessage) },
            confirmButton = {
                TextButton(onClick = { showAlert = false }) {
                    Text("确定")
                }
            },
        )
    }
}