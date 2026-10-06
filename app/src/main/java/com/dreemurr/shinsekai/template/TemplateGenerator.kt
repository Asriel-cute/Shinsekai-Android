package com.dreemurr.shinsekai.template

import com.dreemurr.shinsekai.character.Character

// 无 Android 依赖；仅在新建会话时拼装提示词。
object TemplateGenerator {

    private const val NAME_SEP = "，"
    private const val NARRATION = "旁白"
    private const val JSON_REMINDER = "必须以规定的 JSON 格式回复。"
    private const val BLANK_LINE = "\n\n"
    // 仅提示模型按句意拆条，不在前端截断。
    private const val SPEECH_MAX_CHARS = 500

    // 固定文案的容量预估，减少拼装时扩容。
    private const val FIXED_TEXT_CAPACITY = 1536
    private const val SPRITE_LINE_WITH = "str, 对应的立绘ID字符串，例如 01, 02"
    private const val SPRITE_LINE_NONE = "str, 固定为 -1"
    private const val OPENING_GUIDE = "请开始对话, 开始时介绍下用户所处的情境和背景, 以及在做什么事情:"

    fun generate(characters: List<Character>): String {
        require(characters.isNotEmpty()) { "生成模板至少需要一个角色" }

        val names = characters.joinToString(NAME_SEP) { it.name }
        val (spriteChars, plainChars) = characters.partition { it.sprites.isNotEmpty() }
        val settingChars = characters.filter { it.characterSetting.isNotBlank() }

        return buildString(
            FIXED_TEXT_CAPACITY + characters.sumOf { it.name.length + it.emotionTags.length + it.characterSetting.length },
        ) {
            append("出场人物有：").append(names).append(BLANK_LINE)
            appendJsonContract(spriteChars.isNotEmpty())
            appendSpriteGuide(spriteChars)
            appendCharacterSettings(settingChars)
            appendRequirements(names, spriteChars, plainChars)
            append('\n')
            append(OPENING_GUIDE).append(BLANK_LINE)
            append(JSON_REMINDER).append('\n')
        }
    }

    // 去掉末尾重复提醒、跳过空段，JSON 提醒始终收尾。
    fun composeForLlm(scenario: String, system: String): String {
        val prompt = system.trim().removeSuffix(JSON_REMINDER).trim()
        return buildString {
            if (prompt.isNotEmpty()) append(prompt).append(BLANK_LINE)
            if (scenario.isNotBlank()) appendTrimmed(scenario).append(BLANK_LINE)
            append(JSON_REMINDER)
        }
    }

    private fun StringBuilder.appendJsonContract(hasSprites: Boolean) {
        append(
            """
            每次输出时，必须严格使用 JSON 格式，结构为：
            {
            "dialog": [
                {
                "name": "角色名",
                "sprite": "${if (hasSprites) SPRITE_LINE_WITH else SPRITE_LINE_NONE}",
                "speech": "该角色说的简体中文台词",
                }
            ]
            }""".trimIndent(),
        )
        append(BLANK_LINE)
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

    private fun StringBuilder.appendRequirements(
        names: String,
        spriteChars: List<Character>,
        plainChars: List<Character>,
    ) {
        append("要求：\n")
        rule("格式严格：输出内容必须严格且仅为 JSON 格式，不得包含任何附加的解释、说明或问候语。")
        rule("角色名限制：name 字段只能是以下之一：$names 以及其他可能出现的人物名，或者固定关键字：$NARRATION。")
        appendSpriteRules(spriteChars, plainChars)
        rule("台词风格：speech 字段中的台词须为简体中文，内容须严格符合角色个性、说话方式与背景设定。")
        rule("台词纯净：speech 字段只能包含角色实际说出口的台词，不得混入动作、表情、心理等描写，也不得用括号（如「（微笑）」「（心想）」）或星号包住描写；这类内容只能由 $NARRATION 条目表达。")
        rule("篇幅控制：每个元素的 speech 字段不超过 $SPEECH_MAX_CHARS 字左右；内容多时必须按句意拆成多个元素，即同一角色连续说话就拆成多个元素。")
        rule("数组结构：所有对话和事件必须按时间顺序放入 dialog 数组中，数组中至少包含两个元素。")
        rule("${NARRATION}用途：$NARRATION 元素用于描写场景变化、人物动作、心理活动和环境气氛。")
        rule("${NARRATION}节制：除开场介绍外，旁白只用于必要的场景变化、动作和心理活动；能由台词表达的内容尽量不要写旁白，也不要连续输出多条旁白。")
    }

    private fun StringBuilder.appendSpriteRules(spriteChars: List<Character>, plainChars: List<Character>) {
        if (spriteChars.isEmpty()) return rule("立绘规范：该角色没有可用立绘，sprite 字段必须固定为 -1。")
        rule("立绘规范：sprite 字段必须填写一个两位数字代号（例如 01, 02），并根据当前台词语气和情绪自动选择最合适的立绘。")
        if (plainChars.isNotEmpty()) rule("无立绘角色：${plainChars.joinToString(NAME_SEP) { it.name }} 的 sprite 字段固定为 -1。")
        rule("非立绘角色：当 name 为 $NARRATION 时，sprite 字段必须固定为 -1。")
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
