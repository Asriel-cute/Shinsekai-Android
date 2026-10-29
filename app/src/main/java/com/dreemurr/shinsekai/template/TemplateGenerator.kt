package com.dreemurr.shinsekai.template

import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.voice.VoiceLanguages

// 无 Android 依赖；仅在新建会话时拼装提示词。
object TemplateGenerator {

    // composeForLlm 会 removeSuffix 后重新追加，两处必须完全一致，故保留为常量。
    private const val JSON_REMINDER = "必须以规定的 JSON 格式回复。"

    fun generate(
        characters: List<Character>,
        voiceLanguages: Map<String, String> = emptyMap(),
        playerCharacterId: String? = null,
    ): String {
        require(characters.isNotEmpty()) { "生成模板至少需要一个角色" }

        // 至少两人时主控才有效；找不到角色按未设置处理。
        val player = playerCharacterId
            ?.takeIf { characters.size >= 2 }
            ?.let { id -> characters.firstOrNull { it.id == id } }

        val names = characters.joinToString("，") { it.name }
        val (spriteChars, plainChars) = characters.partition { it.sprites.isNotEmpty() }
        val settingChars = characters.filter { it.characterSetting.isNotBlank() }
        // 有语言快照才要求 translate；按角色 id 取会话创建时的快照，Auto 由模型自判语言。
        val translationTargets = characters.mapNotNull { character ->
            val snapshot = voiceLanguages[character.id] ?: return@mapNotNull null
            val name = when (VoiceLanguages.normalize(snapshot)) {
                VoiceLanguages.JA -> "日语"
                VoiceLanguages.EN -> "英语"
                VoiceLanguages.ZH -> "中文"
                else -> "自动"
            }
            character.name to name
        }

        // 1536 为固定文案的容量预估，减少拼装时扩容。
        return buildString(
            1536 + characters.sumOf { it.name.length + it.emotionTags.length + it.characterSetting.length },
        ) {
            append("出场人物有：").append(names).append("\n\n")
            appendJsonContract()
            appendSpriteGuide(spriteChars)
            appendCharacterSettings(settingChars)
            appendPlayerSection(player)
            appendRequirements(names, spriteChars, plainChars, translationTargets)
            append('\n')
            append("请开始对话，开始时介绍用户所处的情境和背景，以及正在做的事情：").append("\n\n")
            append(JSON_REMINDER).append('\n')
        }
    }

    // 去掉末尾重复提醒、跳过空段，JSON 提醒始终收尾。
    fun composeForLlm(scenario: String, system: String): String {
        val prompt = system.trim().removeSuffix(JSON_REMINDER).trim()
        return buildString {
            if (prompt.isNotEmpty()) append(prompt).append("\n\n")
            if (scenario.isNotBlank()) appendTrimmed(scenario).append("\n\n")
            append(JSON_REMINDER)
        }
    }

    private fun StringBuilder.appendJsonContract() {
        append(
            """
            每次输出时，必须严格使用 JSON 格式，结构为：
            {
            "dialog": [
                {
                "name": "角色名",
                "sprite": "str, 立绘代号，无立绘或旁白固定为 -1",
                "speech": "前端展示的台词",
                "translate": "语音合成用的朗读文本（无要求的角色省略）"
                }
            ]
            }""".trimIndent(),
        )
        append("\n\n")
    }

    private fun StringBuilder.appendSpriteGuide(spriteChars: List<Character>) {
        if (spriteChars.isEmpty()) return
        append("立绘说明:\n")
        for (character in spriteChars) {
            append(character.name).append("有").append(character.sprites.size).append("张立绘：\n")
            if (character.emotionTags.isNotBlank()) appendTrimmed(character.emotionTags).append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.appendCharacterSettings(settingChars: List<Character>) {
        if (settingChars.isEmpty()) return
        append("角色说明：\n")
        for (character in settingChars) {
            append("以下是").append(character.name).append("的角色设定：\n")
            appendTrimmed(character.characterSetting).append('\n')
        }
        append('\n')
    }

    // 主控固定语义：AI 可补写日常台词，并按需复述用户实际台词。
    // 字段结构、立绘与翻译规则沿用通用提示词，此处只写主控专属语义。
    private fun StringBuilder.appendPlayerSection(player: Character?) {
        if (player == null) return
        val name = player.name
        append("主控角色：").append(name).append("（由用户扮演，AI 可根据角色设定、背景等补充日常台词和反应）\n")
        append("用户消息代表").append(name)
            .append("已经说出的台词、主动动作及明确表达的心理活动；用户给出的台词、行动和纠正优先。\n")
        append("涉及关键行动、重大选择、承诺或立场时，若用户没有明确表态，可等待用户回复，尽量不替主控决定。\n")
        append("涉及").append(name).append("的旁白应写出").append(name).append("，不要用“你”指代。\n")
        append("主控台词复述：自行判断本轮是否需要把用户台词（可帮助用户补充润色）作为 dialog 首项复述，不必每轮都加；不需要时不添加，直接推进剧情。\n")
        append("复述项不受 500 字左右的篇幅限制，也不必按句意拆条。\n")
    }

    private fun StringBuilder.appendRequirements(
        names: String,
        spriteChars: List<Character>,
        plainChars: List<Character>,
        translationTargets: List<Pair<String, String>>,
    ) {
        append("要求：\n")
        rule("格式严格：输出内容必须严格且仅为 JSON 格式。")
        rule("角色名限制：name 字段只能是以下之一：$names 以及其他可能出现的人物名，或者固定关键字：旁白。")
        appendSpriteRules(spriteChars, plainChars)
        rule("台词风格：speech 字段须为简体中文，内容尽量符合角色个性、说话方式与背景设定。")
        appendTranslationRules(translationTargets)
        // 仅提示模型按句意拆条，不在前端截断。
        rule("篇幅控制：每个元素的 speech 字段不超过 500 字左右；内容多时必须按句意拆成多个元素，即同一角色连续说话或中间情绪变化就拆成多个元素。")
        rule("数组结构：所有对话和事件必须按时间顺序放入 dialog 数组中，数组中至少包含两个元素。")
        rule("旁白用途：旁白元素用于描写场景变化、人物动作、心理活动和环境气氛。")
        //rule("旁白节制：除开场介绍外，旁白只用于必要的场景变化、动作和心理活动；能由台词表达的内容尽量不要写旁白，也不要连续输出多条旁白。")
    }

    private fun StringBuilder.appendTranslationRules(targets: List<Pair<String, String>>) {
        if (targets.isEmpty()) return
        val grouped = targets.groupBy({ it.second }, { it.first })
            .entries
            .joinToString("；") { (language, names) -> "${names.joinToString("、")}→$language" }
        rule("翻译字段：$grouped。上述角色每条台词必须输出目标语言的非空 translate，自动则自行判断目标语言，仅用于语音合成；不得包含动作、表情、心理、旁白或解释等。")
        rule("翻译范围：未列出的角色、旁白、陌生角色及 speech 为空的条目不填 translate。")
    }

    private fun StringBuilder.appendSpriteRules(spriteChars: List<Character>, plainChars: List<Character>) {
        if (spriteChars.isEmpty()) return rule("立绘规范：该角色没有可用立绘，sprite 字段必须固定为 -1。")
        rule("立绘规范：sprite 字段必须填写一个两位数字代号（例如 01, 02），并根据当前台词语气和情绪自动选择最合适的立绘。")
        if (plainChars.isNotEmpty()) rule("无立绘角色：${plainChars.joinToString("，") { it.name }} 的 sprite 字段固定为 -1。")
        rule("非立绘角色：当 name 为旁白时，sprite 字段必须固定为 -1。")
    }

    // 按索引修剪，避免复制整段设定。
    private fun StringBuilder.appendTrimmed(text: String): StringBuilder {
        var start = 0
        var end = text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return append(text, start, end)
    }

    private fun StringBuilder.rule(text: String) {
        append("- ").append(text).append('\n')
    }
}
