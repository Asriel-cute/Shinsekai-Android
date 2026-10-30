package com.dreemurr.shinsekai.background

import com.dreemurr.shinsekai.character.Conflict
import com.dreemurr.shinsekai.character.ConflictDecision
import com.dreemurr.shinsekai.character.ImportError
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
    val raws: List<RawBackground>,
    val warnings: List<String>,
    val sourceFileName: String?,
)

// 同名冲突先返回 NeedsDecision，再由 resolve 提交或 cancel 清理。
class BackgroundRepository(
    rootDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store = BackgroundStore(rootDir)
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, ImportSession>()

    private val _backgrounds = MutableStateFlow<List<Background>>(emptyList())
    val backgrounds: StateFlow<List<Background>> = _backgrounds.asStateFlow()

    // 仅启动时调用，中途加载会使待决议会话失效。
    suspend fun load() = mutex.withLock {
        withContext(io) {
            store.ensureLayout()
            sessions.clear()
            _backgrounds.value = store.loadAll()
        }
    }

    // 包头探测、整包拷贝和流式回退需要重复打开同一来源。
    suspend fun import(
        openStream: () -> InputStream,
        sourceFileName: String? = null,
    ): BackgroundImportOutcome = mutex.withLock {
        withContext(io) { importLocked(openStream, sourceFileName) }
    }

    // Overwrite 沿用旧 id；KeepBoth 新建 id 并为名称加序号。
    suspend fun resolve(token: String, decision: ConflictDecision): BackgroundImportOutcome = mutex.withLock {
        withContext(io) {
            val session = sessions[token]
                ?: return@withContext BackgroundImportOutcome.Failed(ImportError.SessionNotFound)
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
            val deleted = store.deleteBackground(id)
            if (deleted) _backgrounds.value = _backgrounds.value.filterNot { it.id == id }
            deleted
        }
    }

    // 接收 0 基下标，越界或缺素材返回 null。
    fun sceneImage(id: String, sceneIndex: Int): File? =
        sceneAsset(id, sceneIndex, Background::sprites, "sprites")

    fun bgmFile(id: String, bgmIndex: Int): File? =
        sceneAsset(id, bgmIndex, Background::bgm, "bgm")

    private fun sceneAsset(
        id: String,
        index: Int,
        selector: (Background) -> List<String?>,
        dirName: String,
    ): File? {
        if (index < 0) return null
        val fileName = _backgrounds.value
            .firstOrNull { it.id == id }
            ?.let(selector)
            ?.getOrNull(index)
            ?: return null
        return File(File(store.backgroundDir(id), dirName), fileName).takeIf { it.isFile }
    }

    private fun importLocked(openStream: () -> InputStream, sourceFileName: String?): BackgroundImportOutcome {
        val token = UUID.randomUUID().toString()
        val stagingDir = store.stagingDir(token)
        val rawDir = File(stagingDir, "raw")
        return try {
            val (raws, warnings) = BackgroundYamlParser.parse(BackgroundPackageReader.extract(openStream, rawDir))
            val existing = backgroundsByName()
            val conflicts = raws.mapNotNull { raw ->
                existing[raw.name]?.let { Conflict(incomingName = raw.name, existingName = it.name) }
            }
            val session = ImportSession(token, stagingDir, rawDir, raws, warnings, sourceFileName)
            if (conflicts.isEmpty()) {
                commitSession(session, decision = null)
            } else {
                sessions[token] = session
                BackgroundImportOutcome.NeedsDecision(token, conflicts)
            }
        } catch (e: BackgroundPackageException) {
            failImport(stagingDir, e.error)
        } catch (e: Exception) {
            e.printStackTrace()
            failImport(stagingDir, ImportError.Io)
        }
    }

    private fun failImport(stagingDir: File, error: ImportError): BackgroundImportOutcome {
        stagingDir.deleteRecursively()
        return BackgroundImportOutcome.Failed(error)
    }

    private fun commitSession(session: ImportSession, decision: ConflictDecision?): BackgroundImportOutcome = try {
        val existing = backgroundsByName()
        val usedNames = existing.keys.toMutableSet()
        val assets = store.indexAssets(session.rawDir)
        val warnings = session.warnings.toMutableList()
        val copiedAssets = mutableSetOf<File>()
        val imported = mutableListOf<Background>()

        for (raw in session.raws) {
            val (background, materialized) = installOne(raw, session, decision, existing, usedNames, assets)
            imported += background
            warnings += materialized.warnings
            copiedAssets += materialized.copiedAssets
        }

        val unused = assets.countUnused(copiedAssets)
        if (unused > 0) warnings += "包内 $unused 个素材未被任何背景引用，已忽略"

        sessions.remove(session.token)
        session.stagingDir.deleteRecursively()
        mergeCommitted(imported)
        BackgroundImportOutcome.Imported(imported, warnings)
    } catch (e: BackgroundPackageException) {
        e.printStackTrace()
        abortCommit(session, e.error)
    } catch (e: Exception) {
        e.printStackTrace()
        abortCommit(session, ImportError.Io)
    }

    private fun installOne(
        raw: RawBackground,
        session: ImportSession,
        decision: ConflictDecision?,
        existing: Map<String, Background>,
        usedNames: MutableSet<String>,
        assets: BackgroundAssetIndex,
    ): Pair<Background, MaterializedBackground> {
        val conflict = existing[raw.name]
        val overwrite = conflict != null && decision == ConflictDecision.Overwrite
        val id = if (overwrite) conflict.id else UUID.randomUUID().toString()
        val name = if (overwrite) raw.name else uniqueName(raw.name, usedNames)
        usedNames += name

        val outDir = File(File(session.stagingDir, "out"), id)
        val materialized = store.materialize(assets, raw, outDir)
        val background = Background(
            id = id,
            name = name,
            sprites = materialized.sprites,
            bgTags = raw.bgTags,
            bgm = materialized.bgm,
            bgmTags = raw.bgmTags,
            sourceFileName = session.sourceFileName.orEmpty(),
            importedAt = System.currentTimeMillis(),
        )
        store.writeBackgroundJson(outDir, background)
        store.install(outDir, id)
        return background to materialized
    }

    // 批量安装可能部分成功，失败后以内存重载反映磁盘现状。
    private fun abortCommit(session: ImportSession, error: ImportError): BackgroundImportOutcome {
        sessions.remove(session.token)
        session.stagingDir.deleteRecursively()
        _backgrounds.value = store.loadAll()
        return BackgroundImportOutcome.Failed(error)
    }

    private fun mergeCommitted(imported: List<Background>) {
        val ids = imported.mapTo(HashSet<String>()) { it.id }
        _backgrounds.value = (_backgrounds.value.filterNot { it.id in ids } + imported)
            .sortedBy { it.importedAt }
    }

    // 即使旧数据有重名，也保持原先 firstOrNull 的匹配顺序。
    private fun backgroundsByName(): Map<String, Background> = buildMap {
        for (background in _backgrounds.value) putIfAbsent(background.name, background)
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
