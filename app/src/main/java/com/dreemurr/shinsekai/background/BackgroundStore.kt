package com.dreemurr.shinsekai.background

import com.dreemurr.shinsekai.character.ImportError
import com.dreemurr.shinsekai.core.appJson
import com.dreemurr.shinsekai.core.cleanFileName
import com.dreemurr.shinsekai.core.uniqueAssetName
import com.dreemurr.shinsekai.core.writeTextAtomic
import java.io.File
import java.io.IOException
import java.nio.file.Files

// 素材索引只在本轮导入中复用，不跨导入缓存。
internal class BackgroundAssetIndex(
    val images: Map<String, List<File>>,
    val bgms: Map<String, List<File>>,
) {
    fun countUnused(copiedAssets: Set<File>): Int =
        images.values.sumOf { files -> files.count { it !in copiedAssets } } +
            bgms.values.sumOf { files -> files.count { it !in copiedAssets } }
}

internal class MaterializedBackground(
    val sprites: List<String?>,
    val bgm: List<String?>,
    val copiedAssets: Set<File>,
    val warnings: List<String>,
)

// backgrounds/{uuid}/：background.json、sprites/、bgm/；.staging/{token}/ 暂存导入。
internal class BackgroundStore(private val rootDir: File) {

    fun ensureLayout() {
        rootDir.mkdirs()
        // 待决议会话只存内存，启动时清理残留目录。
        File(rootDir, STAGING_DIR).deleteRecursively()
    }

    fun stagingDir(token: String): File = File(rootDir, "$STAGING_DIR/$token")

    // 损坏目录跳过，按导入时间排序。
    fun loadAll(): List<Background> {
        val dirs = rootDir.listFiles { file -> file.isDirectory && file.name != STAGING_DIR }
            ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching {
                appJson.decodeFromString(Background.serializer(), File(dir, BACKGROUND_JSON).readText())
            }.getOrNull()
        }.sortedBy { it.importedAt }
    }

    fun backgroundDir(id: String): File = File(rootDir, id)

    fun indexAssets(rawDir: File): BackgroundAssetIndex = BackgroundAssetIndex(
        images = indexFiles(File(rawDir, "sprites")),
        bgms = indexFiles(File(rawDir, "bgm")),
    )

    // 缺失或歧义引用留 null，不删项；同时复制 prefix 下的全部素材。
    fun materialize(assets: BackgroundAssetIndex, raw: RawBackground, outDir: File): MaterializedBackground {
        val warnings = mutableListOf<String>()
        val imageRefs = raw.sprites.map { name -> name?.let { resolve(assets.images, raw.spritePrefix, it) } }
        val bgmRefs = raw.bgm.map { name -> name?.let { resolve(assets.bgms, raw.spritePrefix, it) } }

        raw.sprites.forEachIndexed { index, name ->
            if (name != null && imageRefs[index] == null) {
                warnings += "「${raw.name}」第 ${index + 1} 个场景在包内找不到图片，已留空"
            }
        }
        raw.bgm.forEachIndexed { index, name ->
            if (name != null && bgmRefs[index] == null) {
                warnings += "「${raw.name}」第 ${index + 1} 首背景音乐在包内找不到音频，已留空"
            }
        }

        val imageNames = copyAssets(imageRefs, assets.images, raw.spritePrefix, File(outDir, "sprites"))
        val bgmNames = copyAssets(bgmRefs, assets.bgms, raw.spritePrefix, File(outDir, "bgm"))
        return MaterializedBackground(
            sprites = imageRefs.map { ref -> ref?.let { imageNames[it] } },
            bgm = bgmRefs.map { ref -> ref?.let { bgmNames[it] } },
            copiedAssets = imageNames.keys + bgmNames.keys,
            warnings = warnings,
        )
    }

    // 临时文件写完后再替换。
    fun writeBackgroundJson(dir: File, background: Background) {
        try {
            writeTextAtomic(
                File(dir, BACKGROUND_JSON),
                appJson.encodeToString(Background.serializer(), background),
            )
        } catch (e: IOException) {
            throw BackgroundPackageException(ImportError.Io, "写入 $BACKGROUND_JSON 失败: ${background.name}", e)
        }
    }

    // 覆盖时先备份旧目录，安装失败则回滚。
    fun install(outDir: File, id: String) {
        val finalDir = backgroundDir(id)
        var backup: File? = null
        try {
            if (finalDir.exists()) {
                val tokenRoot = outDir.parentFile?.parentFile ?: File(rootDir, STAGING_DIR)
                backup = File(tokenRoot, "old/$id")
                backup.parentFile?.mkdirs()
                move(finalDir, backup)
            }
            move(outDir, finalDir)
        } catch (e: IOException) {
            if (backup != null && backup.exists() && !finalDir.exists()) {
                runCatching { move(backup, finalDir) }
            }
            throw BackgroundPackageException(ImportError.Io, "安装背景目录失败: $id", e)
        }
    }

    fun deleteBackground(id: String): Boolean {
        val dir = backgroundDir(id)
        return dir.isDirectory && dir.deleteRecursively()
    }

    // basename -> 候选文件；保留遍历顺序，确保同名素材后缀稳定。
    private fun indexFiles(dir: File): Map<String, List<File>> {
        if (!dir.isDirectory) return emptyMap()
        val result = mutableMapOf<String, MutableList<File>>()
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            result.getOrPut(file.name) { mutableListOf() }.add(file)
        }
        return result
    }

    // 唯一候选直接采用；多候选时只接受 prefix 下唯一匹配。
    private fun resolve(index: Map<String, List<File>>, prefix: String, fileName: String): File? {
        val candidates = index[fileName] ?: return null
        if (candidates.size == 1) return candidates[0]
        if (prefix.isEmpty()) return null
        return candidates.singleOrNull { hasPrefixSegment(it, prefix) }
    }

    private fun copyAssets(
        refs: List<File?>,
        index: Map<String, List<File>>,
        prefix: String,
        destDir: File,
    ): Map<File, String?> {
        val sources = refs.filterNotNullTo(mutableSetOf())
        if (prefix.isNotEmpty()) {
            for (files in index.values) {
                files.filterTo(sources) { hasPrefixSegment(it, prefix) }
            }
        }
        if (sources.isEmpty()) return emptyMap()
        val copier = AssetCopier(destDir)
        return sources.associateWith(copier::copy)
    }

    // 任意一级父目录名等于 prefix 即视为归属。
    private fun hasPrefixSegment(file: File, prefix: String): Boolean {
        var parent = file.parentFile
        while (parent != null) {
            if (parent.name == prefix) return true
            parent = parent.parentFile
        }
        return false
    }

    // 来源在 copyAssets 中去重；这里仅处理落盘文件名冲突。
    private class AssetCopier(private val destDir: File) {
        private val usedNames = mutableSetOf<String>()

        init {
            destDir.mkdirs()
        }

        fun copy(source: File): String? {
            val name = uniqueAssetName(cleanFileName(source.name), usedNames)
            return try {
                source.copyTo(File(destDir, name), overwrite = false)
                usedNames += name
                name
            } catch (_: IOException) {
                null
            }
        }
    }

    // 同分区移动失败时回退为复制后删除。
    private fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath())
        } catch (e: Exception) {
            try {
                target.parentFile?.mkdirs()
                source.copyRecursively(target, overwrite = true)
                source.deleteRecursively()
            } catch (copyError: Exception) {
                throw IOException("移动目录失败: ${source.name}", copyError)
            }
        }
    }

    private companion object {
        const val STAGING_DIR = ".staging"
        const val BACKGROUND_JSON = "background.json"
    }
}
