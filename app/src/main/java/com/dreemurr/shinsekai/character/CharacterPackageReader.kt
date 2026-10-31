package com.dreemurr.shinsekai.character

import com.dreemurr.shinsekai.core.ZipPackageException
import com.dreemurr.shinsekai.core.ZipPackageExtractor
import com.dreemurr.shinsekai.core.ZipPackageSpec
import java.io.File
import java.io.InputStream

// 只读取根目录 character.yaml 和 sprites/、speech/；解包逻辑见 ZipPackageExtractor。
internal object CharacterPackageReader {

    private val spec = ZipPackageSpec(
        label = "角色包",
        yamlName = "character.yaml",
        assetDirs = listOf("sprites/", "speech/"),
    )

    // open 必须能重复打开同一来源；结构错误转换为角色包领域异常。
    fun extract(open: () -> InputStream, rawDir: File): ByteArray =
        try {
            ZipPackageExtractor.extract(open, rawDir, spec)
        } catch (e: ZipPackageException) {
            throw CharacterPackageException(e.error, e.message ?: "", e.cause)
        }
}
