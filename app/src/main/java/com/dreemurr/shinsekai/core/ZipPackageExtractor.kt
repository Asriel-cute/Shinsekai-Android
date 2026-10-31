package com.dreemurr.shinsekai.core

import com.dreemurr.shinsekai.character.ImportError
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

// 角色包/背景包共用的 ZIP 解包配置：yaml 名、素材目录与错误文案前缀。
internal class ZipPackageSpec(
    val label: String,
    val yamlName: String,
    val assetDirs: List<String>,
)

// 解包过程的结构性错误；由两类包 Reader 转换为各自的领域异常。
internal class ZipPackageException(
    val error: ImportError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

// 只读取根目录 yaml 与指定素材目录；信任包内路径，仅归一化分隔符。
// PK 头先暂存整包并用 ZipFile 按需解压（避免流式穿越大体量素材）；失败回退 ZipInputStream。
internal object ZipPackageExtractor {

    // open 必须能重复打开同一来源。
    fun extract(open: () -> InputStream, rawDir: File, spec: ZipPackageSpec): ByteArray {
        if (!open().use(::looksLikeZip)) {
            return open().use { extractStreaming(it, rawDir, spec) }
        }
        return tryFastPath(open, rawDir, spec)
    }

    // 结构错误或写盘失败直接抛出；其他失败清理半成品后回退流式。
    private fun tryFastPath(open: () -> InputStream, rawDir: File, spec: ZipPackageSpec): ByteArray {
        val tmp = File(rawDir.parentFile, "package.zip")
        try {
            copyTo(open(), tmp)
            return extractRandomAccess(tmp, rawDir, spec)
        } catch (e: ZipPackageException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            rawDir.deleteRecursively()
        } finally {
            tmp.delete()
        }
        return open().use { extractStreaming(it, rawDir, spec) }
    }

    private fun copyTo(input: InputStream, target: File) {
        target.parentFile?.mkdirs()
        input.buffered(64 * 1024).use { source ->
            target.outputStream().buffered(64 * 1024).use { out ->
                source.copyTo(out, 64 * 1024)
            }
        }
    }

    private fun extractRandomAccess(file: File, rawDir: File, spec: ZipPackageSpec): ByteArray {
        var yamlBytes: ByteArray? = null
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val path = normalizeEntryPath(entry.name) ?: continue
                when {
                    path.equals(spec.yamlName, ignoreCase = true) ->
                        yamlBytes = zip.getInputStream(entry).use { it.readBytes() }

                    isAssetPath(path, spec) ->
                        zip.getInputStream(entry).use { input -> writeFile(input, File(rawDir, path)) }
                }
            }
        }
        return yamlBytes ?: throw ZipPackageException(
            ImportError.MissingYaml,
            "包内未找到 ${spec.yamlName}",
        )
    }

    private fun extractStreaming(input: InputStream, rawDir: File, spec: ZipPackageSpec): ByteArray {
        var entriesSeen = 0
        var yamlBytes: ByteArray? = null
        val zip = ZipInputStream(input.buffered(64 * 1024))
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                entriesSeen++
                val path = normalizeEntryPath(entry.name) ?: continue
                when {
                    path.equals(spec.yamlName, ignoreCase = true) -> yamlBytes = zip.readBytes()
                    isAssetPath(path, spec) -> writeFile(zip, File(rawDir, path))
                }
                zip.closeEntry()
            }
        } catch (e: ZipException) {
            throw ZipPackageException(
                if (entriesSeen == 0) ImportError.NotAZip else ImportError.BrokenArchive,
                "${spec.label}读取失败: ${e.message}",
                e,
            )
        } catch (e: IOException) {
            throw ZipPackageException(ImportError.Io, "${spec.label}读取失败: ${e.message}", e)
        }

        // 无 PK 头时 ZipInputStream 可能直接返回 null，不会抛异常。
        if (entriesSeen == 0) throw ZipPackageException(ImportError.NotAZip, "不是有效的 ZIP 包")
        return yamlBytes ?: throw ZipPackageException(ImportError.MissingYaml, "包内未找到 ${spec.yamlName}")
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

    private fun isAssetPath(path: String, spec: ZipPackageSpec): Boolean =
        spec.assetDirs.any { path.startsWith(it, ignoreCase = true) }

    private fun writeFile(input: InputStream, target: File) {
        try {
            target.parentFile?.mkdirs()
            target.outputStream().buffered(64 * 1024).use { out ->
                input.copyTo(out, 64 * 1024)
            }
        } catch (e: IOException) {
            throw ZipPackageException(ImportError.Io, "解压素材失败: ${target.name}", e)
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
}
