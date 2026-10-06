package com.dreemurr.shinsekai.character

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

// 只读取根目录 character.yaml 和 sprites/、speech/；信任包内路径，仅归一化分隔符。
// PK 头先暂存整包并用 ZipFile 按需解压，避免流式穿越 models/；失败回退 ZipInputStream。
internal object CharacterPackageReader {

    private const val IO_BUFFER_SIZE = 64 * 1024
    private const val PACKAGE_TMP = "package.zip"

    // open 必须能重复打开同一来源。
    fun extract(open: () -> InputStream, rawDir: File): ByteArray {
        if (!open().use(::looksLikeZip)) {
            return open().use { extractStreaming(it, rawDir) }
        }
        return tryFastPath(open, rawDir)
    }

    // 结构错误或写盘失败直接抛出；其他失败清理半成品后回退流式。
    private fun tryFastPath(open: () -> InputStream, rawDir: File): ByteArray {
        val tmp = File(rawDir.parentFile, PACKAGE_TMP)
        try {
            copyTo(open(), tmp)
            return extractRandomAccess(tmp, rawDir)
        } catch (e: CharacterPackageException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            rawDir.deleteRecursively()
        } finally {
            tmp.delete()
        }
        return open().use { extractStreaming(it, rawDir) }
    }

    private fun copyTo(input: InputStream, target: File) {
        target.parentFile?.mkdirs()
        input.buffered(IO_BUFFER_SIZE).use { source ->
            target.outputStream().buffered(IO_BUFFER_SIZE).use { out ->
                source.copyTo(out, IO_BUFFER_SIZE)
            }
        }
    }

    private fun extractRandomAccess(file: File, rawDir: File): ByteArray {
        var yamlBytes: ByteArray? = null
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val path = normalizeEntryPath(entry.name) ?: continue
                when {
                    path.equals("character.yaml", ignoreCase = true) ->
                        yamlBytes = zip.getInputStream(entry).use { it.readBytes() }

                    isAssetPath(path) ->
                        zip.getInputStream(entry).use { input -> writeFile(input, File(rawDir, path)) }
                }
            }
        }
        return yamlBytes ?: throw missingYaml()
    }

    private fun extractStreaming(input: InputStream, rawDir: File): ByteArray {
        var entriesSeen = 0
        var yamlBytes: ByteArray? = null
        val zip = ZipInputStream(input.buffered(IO_BUFFER_SIZE))
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                entriesSeen++
                val path = normalizeEntryPath(entry.name) ?: continue
                when {
                    path.equals("character.yaml", ignoreCase = true) -> yamlBytes = zip.readBytes()
                    isAssetPath(path) -> writeFile(zip, File(rawDir, path))
                }
                zip.closeEntry()
            }
        } catch (e: ZipException) {
            throw CharacterPackageException(
                if (entriesSeen == 0) ImportError.NotAZip else ImportError.BrokenArchive,
                "角色包读取失败: ${e.message}",
                e,
            )
        } catch (e: IOException) {
            throw CharacterPackageException(ImportError.Io, "角色包读取失败: ${e.message}", e)
        }

        // 无 PK 头时 ZipInputStream 可能直接返回 null，不会抛异常。
        if (entriesSeen == 0) throw CharacterPackageException(ImportError.NotAZip, "不是有效的 ZIP 包")
        return yamlBytes ?: throw missingYaml()
    }

    // 仅识别 PK\x03\x04 / PK\x05\x06，避免暂存误选的大文件。
    private fun looksLikeZip(input: InputStream): Boolean {
        val head = ByteArray(4)
        var n = 0
        while (n < 4) {
            val read = input.read(head, n, 4 - n)
            if (read < 0) return false
            n += read
        }
        val pk = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        val localFileHeader = head[2] == 3.toByte() && head[3] == 4.toByte()
        val emptyArchive = head[2] == 5.toByte() && head[3] == 6.toByte()
        return pk && (localFileHeader || emptyArchive)
    }

    private fun isAssetPath(path: String): Boolean =
        path.startsWith("sprites/", ignoreCase = true) || path.startsWith("speech/", ignoreCase = true)

    private fun writeFile(input: InputStream, target: File) {
        try {
            target.parentFile?.mkdirs()
            target.outputStream().buffered(IO_BUFFER_SIZE).use { out ->
                input.copyTo(out, IO_BUFFER_SIZE)
            }
        } catch (e: IOException) {
            throw CharacterPackageException(ImportError.Io, "解压素材失败: ${target.name}", e)
        }
    }

    // 跳过目录、空路径和含 NUL 的条目，不做路径安全校验。
    private fun normalizeEntryPath(rawName: String): String? {
        var path = rawName.replace('\\', '/').trim()
        while (path.startsWith("./")) path = path.substring(2)
        if (path.isEmpty() || path.endsWith("/")) return null
        if ('\u0000' in path) return null
        return path
    }

    private fun missingYaml(): CharacterPackageException =
        CharacterPackageException(ImportError.MissingYaml, "包内未找到 character.yaml")
}
