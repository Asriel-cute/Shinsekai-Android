package com.dreemurr.shinsekai.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// SAF 查询显示名；失败返回 null，统一挂 IO。
internal suspend fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.onFailure { it.printStackTrace() }
            .getOrNull()
    }
