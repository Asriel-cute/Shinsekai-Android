package com.dreemurr.shinsekai.character

import java.io.File
import java.io.IOException
import java.nio.file.Files

// 素材索引只在本轮导入中复用，不跨导入缓存。
internal class PackageAssetIndex(
    val images: Map<String, List<File>>,
    val voices: Map<String, List<File>>,
) {
    fun countUnused(copiedAssets: Set<File>): Int =
        images.values.sumOf { files -> files.count { it !in copiedAssets } } +
            voices.values.sumOf { files -> files.count { it !in copiedAssets } }
}

internal class MaterializedCharacter(
    val sprites: List<CharacterSprite>,
    val copiedAssets: Set<File>,
    val warnings: List<String>,
)

// characters/{uuid}/：character.json、sprites/、speech/；.staging/{token}/ 暂存导入。
internal class CharacterStore(private val rootDir: File) {

    fun ensureLayout() {
        rootDir.mkdirs()
        // 待决议会话只存内存，启动时清理残留目录。
        File(rootDir, STAGING_DIR).deleteRecursively()
    }

    fun stagingDir(token: String): File = File(rootDir, "$STAGING_DIR/$token")

    // 损坏目录跳过，按导入时间排序。
    fun loadAll(): List<Character> {
        val dirs = rootDir.listFiles { file -> file.isDirectory && file.name != STAGING_DIR }
            ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching {
                characterJson.decodeFromString(Character.serializer(), File(dir, CHARACTER_JSON).readText())
            }.getOrNull()
        }.sortedBy { it.importedAt }
    }

    fun characterDir(id: String): File = File(rootDir, id)

    fun indexAssets(rawDir: File): PackageAssetIndex = PackageAssetIndex(
        images = indexFiles(File(rawDir, "sprites")),
        voices = indexFiles(File(rawDir, "speech")),
    )

    // 缺失或歧义引用留空，不删项；同时复制 prefix 下的全部素材。
    fun materialize(assets: PackageAssetIndex, raw: RawCharacter, outDir: File): MaterializedCharacter {
        val warnings = mutableListOf<String>()
        val imageRefs = raw.sprites.map { it.imageFileName?.let { name -> resolve(assets.images, raw.spritePrefix, name) } }
        val voiceRefs = raw.sprites.map { it.voiceFileName?.let { name -> resolve(assets.voices, raw.spritePrefix, name) } }

        raw.sprites.forEachIndexed { index, sprite ->
            if (sprite.imageFileName != null && imageRefs[index] == null) {
                warnings += "「${raw.name}」第 ${index + 1} 张立绘在包内找不到图片，已留空"
            }
            if (sprite.voiceFileName != null && voiceRefs[index] == null) {
                warnings += "「${raw.name}」第 ${index + 1} 张立绘在包内找不到语音，已留空"
            }
        }

        val imageNames = copyAssets(imageRefs, assets.images, raw.spritePrefix, File(outDir, "sprites"))
        val voiceNames = copyAssets(voiceRefs, assets.voices, raw.spritePrefix, File(outDir, "speech"))
        return MaterializedCharacter(
            sprites = raw.sprites.mapIndexed { index, sprite ->
                CharacterSprite(
                    file = imageRefs[index]?.let { imageNames[it] },
                    voiceFile = voiceRefs[index]?.let { voiceNames[it] },
                    voiceText = sprite.voiceText,
                    voiceType = sprite.voiceType,
                )
            },
            copiedAssets = imageNames.keys + voiceNames.keys,
            warnings = warnings,
        )
    }

    // 临时文件写完后再替换。
    fun writeCharacterJson(dir: File, character: Character) {
        val target = File(dir, CHARACTER_JSON)
        val tmp = File(dir, "$CHARACTER_JSON.tmp")
        try {
            dir.mkdirs()
            tmp.writeText(characterJson.encodeToString(Character.serializer(), character))
            if (!tmp.renameTo(target)) {
                Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw CharacterPackageException(ImportError.Io, "写入 ${CHARACTER_JSON} 失败: ${character.name}", e)
        }
    }

    // 覆盖时先备份旧目录，安装失败则回滚。
    fun install(outDir: File, id: String) {
        val finalDir = characterDir(id)
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
            throw CharacterPackageException(ImportError.Io, "安装角色目录失败: $id", e)
        }
    }

    fun deleteCharacter(id: String): Boolean {
        val dir = characterDir(id)
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
            val name = uniqueName(cleanFileName(source.name))
            return try {
                source.copyTo(File(destDir, name), overwrite = false)
                usedNames += name
                name
            } catch (_: IOException) {
                null
            }
        }

        private fun uniqueName(name: String): String {
            if (name !in usedNames) return name
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var index = 1
            var candidate = "$stem-$index$ext"
            while (candidate in usedNames) {
                index++
                candidate = "$stem-$index$ext"
            }
            return candidate
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
        const val CHARACTER_JSON = "character.json"
    }
}

// 预编译文件名清洗规则。
private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\x00-\\x1f<>:\"/\\\\|?*]")

private fun cleanFileName(raw: String): String {
    val name = ILLEGAL_FILE_NAME_CHARS.replace(raw, "_").trim(' ', '.')
    if (name.isEmpty()) return "asset"
    if (name.length <= MAX_FILE_NAME_LENGTH) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.substring(dot) else ""
    return name.substring(0, MAX_FILE_NAME_LENGTH - ext.length) + ext
}

private const val MAX_FILE_NAME_LENGTH = 120
