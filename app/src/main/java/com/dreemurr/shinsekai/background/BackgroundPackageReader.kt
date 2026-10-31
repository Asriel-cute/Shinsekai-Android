package com.dreemurr.shinsekai.background

import com.dreemurr.shinsekai.core.ZipPackageException
import com.dreemurr.shinsekai.core.ZipPackageExtractor
import com.dreemurr.shinsekai.core.ZipPackageSpec
import java.io.File
import java.io.InputStream

// 只读取根目录 background.yaml 和 sprites/、bgm/；解包逻辑见 ZipPackageExtractor。
internal object BackgroundPackageReader {

    private val spec = ZipPackageSpec(
        label = "背景包",
        yamlName = "background.yaml",
        assetDirs = listOf("sprites/", "bgm/"),
    )

    // open 必须能重复打开同一来源；结构错误转换为背景包领域异常。
    fun extract(open: () -> InputStream, rawDir: File): ByteArray =
        try {
            ZipPackageExtractor.extract(open, rawDir, spec)
        } catch (e: ZipPackageException) {
            throw BackgroundPackageException(e.error, e.message ?: "", e.cause)
        }
}
