package com.dreemurr.shinsekai.character

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

// 只存内存；进程重启后 token 失效，启动时清理 staging。
private class ImportSession(
    val token: String,
    val stagingDir: File,
    val rawDir: File,
    val raws: List<RawCharacter>,
    val warnings: List<String>,
    val sourceFileName: String?,
)

// 同名冲突先返回 NeedsDecision，再由 resolve 提交或 cancel 清理。
class CharacterRepository(
    rootDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store = CharacterStore(rootDir)
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, ImportSession>()

    private val _characters = MutableStateFlow<List<Character>>(emptyList())
    val characters: StateFlow<List<Character>> = _characters.asStateFlow()

    // 仅启动时调用，中途加载会使待决议会话失效。
    suspend fun load() = mutex.withLock {
        withContext(io) {
            store.ensureLayout()
            sessions.clear()
            _characters.value = store.loadAll()
        }
    }

    // 包头探测、整包拷贝和流式回退需要重复打开同一来源。
    suspend fun import(
        openStream: () -> InputStream,
        sourceFileName: String? = null,
    ): ImportOutcome = mutex.withLock {
        withContext(io) { importLocked(openStream, sourceFileName) }
    }

    // Overwrite 沿用旧 id；KeepBoth 新建 id 并为名称加序号。
    suspend fun resolve(token: String, decision: ConflictDecision): ImportOutcome = mutex.withLock {
        withContext(io) {
            val session = sessions[token]
                ?: return@withContext ImportOutcome.Failed(ImportError.SessionNotFound)
            commitSession(session, decision)
        }
    }

    // token 不存在时静默成功。
    suspend fun cancel(token: String) {
        mutex.withLock {
            withContext(io) {
                sessions.remove(token)?.stagingDir?.deleteRecursively()
            }
        }
    }

    suspend fun delete(id: String): Boolean = mutex.withLock {
        withContext(io) {
            val deleted = store.deleteCharacter(id)
            if (deleted) _characters.value = _characters.value.filterNot { it.id == id }
            deleted
        }
    }

    // 接收 0 基下标，越界或缺素材返回 null。
    fun spriteImage(id: String, spriteIndex: Int): File? =
        spriteAsset(id, spriteIndex, CharacterSprite::file, "sprites")

    // 播放策略由调用方决定。
    fun spriteVoice(id: String, spriteIndex: Int): File? =
        spriteAsset(id, spriteIndex, CharacterSprite::voiceFile, "speech")

    private fun spriteAsset(
        id: String,
        spriteIndex: Int,
        selector: (CharacterSprite) -> String?,
        dirName: String,
    ): File? {
        if (spriteIndex < 0) return null
        val sprite = _characters.value
            .firstOrNull { it.id == id }
            ?.sprites
            ?.getOrNull(spriteIndex)
            ?: return null
        val fileName = selector(sprite) ?: return null
        return File(File(store.characterDir(id), dirName), fileName).takeIf { it.isFile }
    }

    private fun importLocked(openStream: () -> InputStream, sourceFileName: String?): ImportOutcome {
        val token = UUID.randomUUID().toString()
        val stagingDir = store.stagingDir(token)
        val rawDir = File(stagingDir, "raw")
        return try {
            val (raws, warnings) = CharacterYamlParser.parse(CharacterPackageReader.extract(openStream, rawDir))
            val existing = charactersByName()
            val conflicts = raws.mapNotNull { raw ->
                existing[raw.name]?.let { Conflict(incomingName = raw.name, existingName = it.name) }
            }
            val session = ImportSession(token, stagingDir, rawDir, raws, warnings, sourceFileName)
            if (conflicts.isEmpty()) {
                commitSession(session, decision = null)
            } else {
                sessions[token] = session
                ImportOutcome.NeedsDecision(token, conflicts)
            }
        } catch (e: CharacterPackageException) {
            failImport(stagingDir, e.error)
        } catch (e: Exception) {
            e.printStackTrace()
            failImport(stagingDir, ImportError.Io)
        }
    }

    private fun failImport(stagingDir: File, error: ImportError): ImportOutcome {
        stagingDir.deleteRecursively()
        return ImportOutcome.Failed(error)
    }

    private fun commitSession(session: ImportSession, decision: ConflictDecision?): ImportOutcome = try {
        val existing = charactersByName()
        val usedNames = existing.keys.toMutableSet()
        val assets = store.indexAssets(session.rawDir)
        val warnings = session.warnings.toMutableList()
        val copiedAssets = mutableSetOf<File>()
        val imported = mutableListOf<Character>()

        for (raw in session.raws) {
            val (character, materialized) = installOne(raw, session, decision, existing, usedNames, assets)
            imported += character
            warnings += materialized.warnings
            copiedAssets += materialized.copiedAssets
        }

        val unused = assets.countUnused(copiedAssets)
        if (unused > 0) warnings += "包内 $unused 个素材未被任何角色引用，已忽略"

        sessions.remove(session.token)
        session.stagingDir.deleteRecursively()
        mergeCommitted(imported)
        ImportOutcome.Imported(imported, warnings)
    } catch (e: CharacterPackageException) {
        e.printStackTrace()
        abortCommit(session, e.error)
    } catch (e: Exception) {
        e.printStackTrace()
        abortCommit(session, ImportError.Io)
    }

    private fun installOne(
        raw: RawCharacter,
        session: ImportSession,
        decision: ConflictDecision?,
        existing: Map<String, Character>,
        usedNames: MutableSet<String>,
        assets: PackageAssetIndex,
    ): Pair<Character, MaterializedCharacter> {
        val conflict = existing[raw.name]
        val overwrite = conflict != null && decision == ConflictDecision.Overwrite
        val id = if (overwrite) conflict.id else UUID.randomUUID().toString()
        val name = if (overwrite) raw.name else uniqueName(raw.name, usedNames)
        usedNames += name

        val outDir = File(File(session.stagingDir, "out"), id)
        val materialized = store.materialize(assets, raw, outDir)
        val character = Character(
            id = id,
            name = name,
            color = raw.color,
            characterSetting = raw.characterSetting,
            emotionTags = raw.emotionTags,
            spriteScale = raw.spriteScale,
            speechSpeed = raw.speechSpeed,
            speechVolume = raw.speechVolume,
            sprites = materialized.sprites,
            sourceFileName = session.sourceFileName.orEmpty(),
            importedAt = System.currentTimeMillis(),
        )
        store.writeCharacterJson(outDir, character)
        store.install(outDir, id)
        return character to materialized
    }

    // 批量安装可能部分成功，失败后以内存重载反映磁盘现状。
    private fun abortCommit(session: ImportSession, error: ImportError): ImportOutcome {
        sessions.remove(session.token)
        session.stagingDir.deleteRecursively()
        _characters.value = store.loadAll()
        return ImportOutcome.Failed(error)
    }

    private fun mergeCommitted(imported: List<Character>) {
        val ids = imported.mapTo(HashSet<String>()) { it.id }
        _characters.value = (_characters.value.filterNot { it.id in ids } + imported)
            .sortedBy { it.importedAt }
    }

    // 即使旧数据有重名，也保持原先 firstOrNull 的匹配顺序。
    private fun charactersByName(): Map<String, Character> = buildMap {
        for (character in _characters.value) putIfAbsent(character.name, character)
    }

    private fun uniqueName(base: String, used: MutableSet<String>): String {
        if (base !in used) return base
        var index = 1
        var candidate = "$base（$index）"
        while (candidate in used) {
            index++
            candidate = "$base（$index）"
        }
        return candidate
    }
}
