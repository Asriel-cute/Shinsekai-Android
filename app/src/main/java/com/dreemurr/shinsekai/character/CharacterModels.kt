package com.dreemurr.shinsekai.character

import kotlinx.serialization.Serializable

// 角色元数据，不保留语音合成模型配置。
@Serializable
data class Character(
    val id: String,
    val name: String,
    val color: String = "",
    val characterSetting: String = "",
    val emotionTags: String = "",
    val spriteScale: Float = 1f,
    val speechSpeed: Float = 1f,
    val speechVolume: Float = 1f,
    // 顺序对应 LLM 的 1 基编号；缺素材也保留条目，禁止删除或重排。
    val sprites: List<CharacterSprite> = emptyList(),
    val sourceFileName: String = "",
    val importedAt: Long = 0,
)

// 切图时播放 preset/fallback 语音，reference 不播放。
@Serializable
data class CharacterSprite(
    // 素材目录内的文件名，null 表示缺失。
    val file: String? = null,
    val voiceFile: String? = null,
    val voiceText: String? = null,
    val voiceType: String? = null,
)

data class Conflict(
    val incomingName: String,
    val existingName: String,
)

enum class ConflictDecision { Overwrite, KeepBoth }

sealed interface ImportOutcome {
    data class Imported(
        val characters: List<Character>,
        val warnings: List<String> = emptyList(),
    ) : ImportOutcome

    // 保留 staging，等待 resolve 或 cancel。
    data class NeedsDecision(
        val token: String,
        val conflicts: List<Conflict>,
    ) : ImportOutcome

    data class Failed(val error: ImportError) : ImportOutcome
}

enum class ImportError {
    NotAZip,
    MissingYaml,
    MalformedYaml,
    EmptyPackage,
    BrokenArchive,
    // 进程重启后待决议会话失效，需重新导入。
    SessionNotFound,
    Io,
}

internal class CharacterPackageException(
    val error: ImportError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
