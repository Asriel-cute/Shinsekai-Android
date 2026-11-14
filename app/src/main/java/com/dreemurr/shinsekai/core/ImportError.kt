package com.dreemurr.shinsekai.core

// 角色包 / 背景包导入共用的错误分类；由 UI 层映射为对应文案。
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
