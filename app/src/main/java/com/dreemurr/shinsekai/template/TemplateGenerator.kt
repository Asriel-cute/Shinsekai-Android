package com.dreemurr.shinsekai.template

import com.dreemurr.shinsekai.background.Background
import com.dreemurr.shinsekai.character.Character
import com.dreemurr.shinsekai.voice.VoiceLanguages

// 无 Android 依赖；新建会话与会话设置保存时拼装提示词。
object TemplateGenerator {

    // composeForLlm 会 removeSuffix 后重新追加，两处必须完全一致，故保留为常量。
    private const val JSON_REMINDER = "必须以规定的 JSON 格式回复。"

    fun generate(
        characters: List<Character>,
        voiceLanguages: Map<String, String> = emptyMap(),
        playerCharacterId: String? = null,
        background: Background? = null,
        videoChat: Boolean = false,
        statEnabled: Boolean = false,
        choiceEnabled: Boolean = false,
    ): String {
        require(characters.isNotEmpty()) { "生成模板至少需要一个角色" }

        // 视频会话与主控互斥；至少两人且角色在选角内主控才有效。
        val player = if (videoChat) null else playerCharacterId
            ?.takeIf { characters.size >= 2 }
            ?.let { id -> characters.firstOrNull { it.id == id } }

        // 无场景图且无 BGM 的空背景包按无背景处理。
        val hasScenes = background != null && background.sprites.isNotEmpty()
        val hasBgm = background != null && background.bgm.isNotEmpty()

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

        // 2048 为固定文案的容量预估，减少拼装时扩容。
        return buildString(
            2048 + characters.sumOf { it.name.length + it.emotionTags.length + it.characterSetting.length } +
                (background?.let { it.bgTags.length + it.bgmTags.length } ?: 0),
        ) {
            append("出场人物有：").append(names).append("\n\n")
            appendJsonContract()
            appendSpriteGuide(spriteChars)
            appendCharacterSettings(settingChars)
            appendBackgroundSection(background, hasScenes, hasBgm)
            appendVideoSection(videoChat)
            appendPlayerSection(player)
            appendRequirements(
                names, spriteChars, plainChars, translationTargets,
                hasScenes = hasScenes, hasBgm = hasBgm, statEnabled = statEnabled, choiceEnabled = choiceEnabled,
            )
            append('\n')
            appendOpening(hasScenes, hasBgm, statEnabled = statEnabled)
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

    // sprite 统一描述为资源编号；无背景会话不会收到 SCENE/bgm 规则，即便误产出也会被安全忽略。
    private fun StringBuilder.appendJsonContract() {
        append(
            """
            每次输出时，必须严格使用 JSON 格式，结构为：
            {
            "dialog": [
                {
                "name": "角色名",
                "sprite": "str, 立绘代号或场景/BGM 编号，无立绘或旁白/CHOICE/STAT 固定为 -1",
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

    // 背景包目录：场景图与 BGM 均为 1 基编号，标签逐行对应；空包不输出任何段落。
    private fun StringBuilder.appendBackgroundSection(
        background: Background?,
        hasScenes: Boolean,
        hasBgm: Boolean,
    ) {
        if (background == null || (!hasScenes && !hasBgm)) return
        if (hasScenes) {
            append("场景说明：\n")
            append("现在有").append(background.sprites.size).append("个可用场景：\n")
            appendTrimmed(background.bgTags).append('\n')
            append('\n')
        }
        if (hasBgm) {
            append("BGM说明：\n")
            append("现在有").append(background.bgm.size).append("个可用BGM：\n")
            appendTrimmed(background.bgmTags).append('\n')
            append('\n')
        }
    }

    // 视频通话段：画面消息与 request_camera 工具语义（主控与视频会话互斥）。
    private fun StringBuilder.appendVideoSection(videoChat: Boolean) {
        if (!videoChat) return
        append("视频通话：你正在与用户进行视频通话。用户的画面会不定期以图像消息（文字标记为「（摄像头画面）」）出现在对话中；看到图像时对画面内容自然反应，没有图像时不要说看不见，按需执行索要画面即可。\n\n")
        append("想看用户当前画面时，调用 request_camera 工具（无参数）发起画面请求；画面会作为工具结果与图像消息送达，随后在同一轮内继续回复并对画面作出反应。除工具调用外，其余输出仍为规定的 JSON。\n\n")
    }

    // 主控固定语义：AI 可补写日常台词并按需复述用户台词，其余规则沿用通用提示词。
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
        hasScenes: Boolean,
        hasBgm: Boolean,
        statEnabled: Boolean,
        choiceEnabled: Boolean,
    ) {
        append("要求：\n")
        rule("格式严格：输出内容必须严格且仅为 JSON 格式。")
        // 非台词条目：立绘、台词风格与翻译范围三处共用同一份名单；保留关键字在其后追加场景/BGM。
        val nonSpeechNames = buildList {
            add("旁白")
            if (choiceEnabled) add("CHOICE")
            if (statEnabled) add("STAT")
        }.joinToString("、")
        val reservedKeywords = buildList {
            add(nonSpeechNames)
            if (hasScenes) add("SCENE")
            if (hasBgm) add("bgm")
        }.joinToString("、")
        rule("角色名限制：name 字段只能是以下之一：$names 以及其他可能出现的人物名，或者固定关键字：$reservedKeywords。")
        appendSpriteRules(spriteChars, plainChars, nonSpeechNames)
        appendPresentationRules(hasScenes, hasBgm)
        rule("台词风格：speech 字段须为简体中文，内容尽量符合角色个性、说话方式与背景设定。除$nonSpeechNames 外不得包含动作、表情、心理、旁白或解释等。")
        appendTranslationRules(translationTargets, nonSpeechNames)
        // 仅提示模型按句意拆条，不在前端截断。
        rule("篇幅控制：每个元素的 speech 字段不超过 500 字左右；内容多时必须按句意拆成多个元素，即同一角色连续说话或中间情绪变化就拆成多个元素。")
        rule("数组结构：所有对话和事件必须按时间顺序放入 dialog 数组中，数组中至少包含两个元素。")
        rule("旁白用途：旁白元素用于描写场景变化、人物动作、心理活动和环境气氛。")
        appendChoiceRules(choiceEnabled)
        appendStatRules(statEnabled)
    }

    // 选项规则与上游一致：结尾必出、'/' 分隔、三种取向各一。
    private fun StringBuilder.appendChoiceRules(choiceEnabled: Boolean) {
        if (!choiceEnabled) return
        rule("选项位置：dialog 数组的最后一个元素必须是 CHOICE。")
        rule("选项格式：CHOICE 的 speech 内用 '/' 分隔子选项。必须是用户可选择的对话或行为的纯文本描述。")
        rule("选项平衡：必须包括一个无厘头、一个精明/理智、以及一个中庸的选项。所有选项必须与当前剧情与人物性格相符。")
    }

    // 数值条目原样留在 LLM 历史里，另有每轮注入的 [当前状态] 块给当前快照；要求整表输出，避免只报变化项。
    private fun StringBuilder.appendStatRules(statEnabled: Boolean) {
        if (!statEnabled) return
        rule(
            "数值显示：STAT 元素表示用户或角色状态。name 为 STAT 时，speech 每行只能使用 " +
                "icon|label|value|max 格式；max 可选。可用 icon：heart、shield、zap、star、sparkles、target、coins、clock、gauge。" +
                "例如：heart|生命|72|100\nzap|精力|30|100 等。",
        )
        rule(
            "数值更新：用户消息前的 [当前状态] 块列出当前的数值。数值随着剧情发展需要变化时请输出增删改后的完整 STAT。",
        )
    }

    private fun StringBuilder.appendTranslationRules(targets: List<Pair<String, String>>, nonSpeechNames: String) {
        if (targets.isEmpty()) return
        val grouped = targets.groupBy({ it.second }, { it.first })
            .entries
            .joinToString("；") { (language, names) -> "${names.joinToString("、")}→$language" }
        rule("翻译字段：$grouped。上述角色每条台词必须输出目标语言的非空 translate，自动则自行判断目标语言，仅用于语音合成；绝对不得包含动作、表情、心理、旁白或解释等。")
        rule("翻译范围：未列出的角色、$nonSpeechNames、陌生角色及 speech 为空的条目不填 translate。")
    }

    private fun StringBuilder.appendSpriteRules(
        spriteChars: List<Character>,
        plainChars: List<Character>,
        nonSpeechNames: String,
    ) {
        if (spriteChars.isEmpty()) return rule("立绘规范：该角色没有可用立绘，sprite 字段必须固定为 -1。")
        rule("立绘规范：sprite 字段必须填写对应立绘的编号（从 1 开始，按立绘说明中的顺序），并根据当前台词语气和情绪自动选择最合适的立绘。")
        if (plainChars.isNotEmpty()) rule("无立绘角色：${plainChars.joinToString("，") { it.name }} 的 sprite 字段固定为 -1。")
        rule("非立绘角色：当 name 为$nonSpeechNames 时，sprite 字段必须固定为 -1。")
    }

    // 立绘/场景/BGM 共用 sprite 字段的 1 基编号语义，规则文案保持同一说法。
    private fun StringBuilder.appendPresentationRules(
        hasScenes: Boolean,
        hasBgm: Boolean,
    ) {
        if (hasScenes) {
            rule("场景切换：当 name 为 SCENE 时，sprite 字段填写场景编号（从 1 开始），如 3 表示切换到第 3 个场景；此时 speech 与 translate 留空。")
        }
        if (hasBgm) {
            rule("BGM切换：当 name 为 bgm 时，sprite 字段填写 BGM 编号（从 1 开始），如 2 表示切换到第 2 首；应根据当前氛围切换；此时 speech 与 translate 留空。")
        }
    }

    // 有背景时开场额外要求设置初始场景与 BGM；开数值时要求给出初始数值。
    private fun StringBuilder.appendOpening(hasScenes: Boolean, hasBgm: Boolean, statEnabled: Boolean) {
        append("请开始对话，开始时介绍用户所处的情境和背景")
        if (hasScenes || hasBgm) {
            append("，设置初始的场景和BGM")
        }
        if (statEnabled) {
            append("， STAT 可给出用户的数值")
        }
        append("，以及正在做的事情：").append("\n\n")
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
