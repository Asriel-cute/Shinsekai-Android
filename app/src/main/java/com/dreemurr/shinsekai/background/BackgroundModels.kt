package com.dreemurr.shinsekai.background

import com.dreemurr.shinsekai.character.Conflict
import com.dreemurr.shinsekai.character.ImportError
import kotlinx.serialization.Serializable

// 背景包元数据；sprites/bgm 顺序对应 LLM 的 1 基编号，缺素材保留 null，禁止删除或重排。
// bgTags/bgmTags 为「场景 N：描述 / 音乐 N：描述」逐行原文，直接进入提示词。
@Serializable
data class Background(
    val id: String,
    val name: String,
    // 素材目录 sprites/ 内的文件名，null 表示缺失。
    val sprites: List<String?> = emptyList(),
    val bgTags: String = "",
    // 素材目录 bgm/ 内的文件名，null 表示缺失。
    val bgm: List<String?> = emptyList(),
    val bgmTags: String = "",
    val sourceFileName: String = "",
    val importedAt: Long = 0,
)

// 与角色导入共用冲突与错误类型（character 包内均为通用形状）；导入结果携带背景包列表。
sealed interface BackgroundImportOutcome {
    data class Imported(
        val backgrounds: List<Background>,
        val warnings: List<String> = emptyList(),
    ) : BackgroundImportOutcome

    // 保留 staging，等待 resolve 或 cancel。
    data class NeedsDecision(
        val token: String,
        val conflicts: List<Conflict>,
    ) : BackgroundImportOutcome

    data class Failed(val error: ImportError) : BackgroundImportOutcome
}

internal class BackgroundPackageException(
    val error: ImportError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
