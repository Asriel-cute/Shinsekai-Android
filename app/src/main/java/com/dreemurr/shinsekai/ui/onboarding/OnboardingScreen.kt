package com.dreemurr.shinsekai.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dreemurr.shinsekai.R
import kotlinx.coroutines.launch

// 每页配一张 Compose 界面示意图。
private enum class OnboardingArt { Welcome, Api, Characters, Session, Chat, Voice }

private data class OnboardingPage(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val points: List<Int>,
    val art: OnboardingArt,
)

private val OnboardingPages = listOf(
    OnboardingPage(
        titleRes = R.string.onboarding_welcome_title,
        bodyRes = R.string.onboarding_welcome_body,
        points = listOf(
            R.string.onboarding_welcome_point1,
            R.string.onboarding_welcome_point2,
        ),
        art = OnboardingArt.Welcome,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_api_title,
        bodyRes = R.string.onboarding_api_body,
        points = listOf(
            R.string.onboarding_api_point1,
            R.string.onboarding_api_point2,
            R.string.onboarding_api_point3,
        ),
        art = OnboardingArt.Api,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_characters_title,
        bodyRes = R.string.onboarding_characters_body,
        points = listOf(
            R.string.onboarding_characters_point1,
            R.string.onboarding_characters_point2,
            R.string.onboarding_characters_point3,
        ),
        art = OnboardingArt.Characters,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_session_title,
        bodyRes = R.string.onboarding_session_body,
        points = listOf(
            R.string.onboarding_session_point1,
            R.string.onboarding_session_point2,
            R.string.onboarding_session_point3,
        ),
        art = OnboardingArt.Session,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_chat_title,
        bodyRes = R.string.onboarding_chat_body,
        points = listOf(
            R.string.onboarding_chat_point1,
            R.string.onboarding_chat_point2,
            R.string.onboarding_chat_point3,
        ),
        art = OnboardingArt.Chat,
    ),
    OnboardingPage(
        titleRes = R.string.onboarding_voice_title,
        bodyRes = R.string.onboarding_voice_body,
        points = listOf(
            R.string.onboarding_voice_point1,
            R.string.onboarding_voice_point2,
            R.string.onboarding_voice_point3,
        ),
        art = OnboardingArt.Voice,
    ),
)

// 首次安装的全屏新手引导：6 页图文一次读完，无中途跳转，保持阅读连续性。
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { OnboardingPages.size })
    val scope = rememberCoroutineScope()
    val lastIndex = OnboardingPages.lastIndex

    fun goToPage(index: Int) {
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    // 返回键回上一页；首页等同跳过，不把用户困在引导里。
    BackHandler {
        if (pagerState.currentPage > 0) goToPage(pagerState.currentPage - 1) else onFinish()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 引导页替换根 Scaffold，需自行处理状态栏/导航栏/刘海 insets。
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp),
        ) {
            // 右上角跳过；末页不显示，避免与主按钮重复。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (pagerState.currentPage < lastIndex) {
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { index ->
                OnboardingPageContent(OnboardingPages[index])
            }

            // 页码圆点。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(OnboardingPages.size) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(width = if (selected) 20.dp else 8.dp, height = 8.dp)
                            .background(
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            ),
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pagerState.currentPage > 0) {
                    TextButton(onClick = { goToPage(pagerState.currentPage - 1) }) {
                        Text(stringResource(R.string.onboarding_previous))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (pagerState.currentPage == lastIndex) onFinish()
                        else goToPage(pagerState.currentPage + 1)
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(
                        stringResource(
                            if (pagerState.currentPage == lastIndex) {
                                R.string.onboarding_start
                            } else {
                                R.string.onboarding_next
                            },
                        ),
                    )
                }
            }
        }
    }
}

// 单页内容：示意图 + 标题 + 正文 + 要点；内容不足一屏时居中，大字体/小屏时内部滚动。
@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            OnboardingArtCard(page.art)
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(page.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(page.bodyRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            page.points.forEach { point ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(14.dp),
                    )
                    Text(
                        stringResource(point),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

// ============ 界面示意图（全部 Compose 绘制，无图片资源） ============

@Composable
private fun OnboardingArtCard(art: OnboardingArt) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(16.dp)) {
            when (art) {
                OnboardingArt.Welcome -> WelcomeArt()
                OnboardingArt.Api -> ApiArt()
                OnboardingArt.Characters -> CharactersArt()
                OnboardingArt.Session -> SessionArt()
                OnboardingArt.Chat -> ChatArt()
                OnboardingArt.Voice -> VoiceArt()
            }
        }
    }
}

// 欢迎：视觉小说舞台 + 对话框 + 旁白标签。
@Composable
private fun WelcomeArt() {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(52.dp),
            )
        }
        MockPanel(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.onboarding_mock_dialog_line),
                    style = MaterialTheme.typography.bodyMedium,
                )
                MockPill(stringResource(R.string.onboarding_mock_narration), selected = false)
            }
        }
    }
}

// 配置 LLM：提供商、连接字段与测试/保存按钮的设置面板小样。
@Composable
private fun ApiArt() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MockPill(stringResource(R.string.provider_openai), selected = true)
            MockPill(stringResource(R.string.provider_anthropic), selected = false)
            MockPill(stringResource(R.string.provider_google), selected = false)
        }
        MockField(stringResource(R.string.settings_base_url_label))
        MockField(stringResource(R.string.settings_api_key_label))
        MockField(stringResource(R.string.settings_model_label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MockButton(
                stringResource(R.string.action_test_connection),
                filled = false,
                modifier = Modifier.weight(1f),
            )
            MockButton(
                stringResource(R.string.action_save),
                filled = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// 导入角色：.char 文件卡 → 角色卡。
@Composable
private fun CharactersArt() {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MockPanel {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(".char", style = MaterialTheme.typography.labelMedium)
                SkeletonBar(Modifier.width(90.dp))
            }
        }
        MockArrowDown()
        MockPanel(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MockAvatar()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.onboarding_mock_character_name),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.character_info_format, 12, 24),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// 新建对话：角色勾选、主控徽章、情景与开始按钮。
@Composable
private fun SessionArt() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MockPanel(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MockCheckBox(checked = true)
                SkeletonBar(Modifier.width(80.dp))
                Spacer(Modifier.weight(1f))
                MockPill(stringResource(R.string.session_setup_player_badge), selected = true)
            }
        }
        MockPanel(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MockCheckBox(checked = false)
                SkeletonBar(Modifier.width(60.dp))
            }
        }
        MockPanel(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    stringResource(R.string.new_session_section_scenario),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.onboarding_mock_scenario),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MockButton(
            stringResource(R.string.action_start_chat),
            filled = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// 对话操作：记录入口、调暗的立绘、对话框与输入栏。
@Composable
private fun ChatArt() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MockPill(stringResource(R.string.chat_record), selected = false)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp),
            )
        }
        MockPanel(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.onboarding_mock_dialog_line_2),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        MockPanel(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.chat_input_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// 语音：右上角「克隆/添加」入口 → 音色卡 → 角色行 + 绑定按钮。
@Composable
private fun VoiceArt() {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 入口在语音页右上角，顺序与真实顶栏一致：先「克隆」后「添加」，样式统一。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MockPill(stringResource(R.string.action_clone), selected = false)
            MockPill(stringResource(R.string.action_add), selected = false)
        }
        MockArrowDown()
        MockPanel(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.onboarding_mock_character_name),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.onboarding_mock_voice_meta),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        MockArrowDown()
        MockPanel(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MockAvatar()
                SkeletonBar(Modifier.width(70.dp))
                Spacer(Modifier.weight(1f))
                MockButton(
                    stringResource(R.string.onboarding_mock_bind),
                    filled = true,
                    modifier = Modifier.width(76.dp),
                )
            }
        }
    }
}

// ============ 示意图通用小件 ============

// 面板：示意图内的白色圆角卡片。
@Composable
private fun MockPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        content()
    }
}

// 骨架条：宽度由调用方给定，示意文本占位。
@Composable
private fun SkeletonBar(modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(10.dp)
            .background(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                RoundedCornerShape(5.dp),
            ),
    )
}

// 胶囊：选中用主题色，未选中描边。
@Composable
private fun MockPill(text: String, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// 按钮：宽度须由调用方给定（weight/fillMaxWidth/width），文字水平居中。
@Composable
private fun MockButton(text: String, filled: Boolean, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (filled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (filled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.primary
        },
        border = if (filled) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// 字段：标签 + 骨架条。
@Composable
private fun MockField(label: String) {
    MockPanel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SkeletonBar(Modifier.width(110.dp))
        }
    }
}

// 勾选框。
@Composable
private fun MockCheckBox(checked: Boolean) {
    Box(
        Modifier
            .size(18.dp)
            .background(
                if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                RoundedCornerShape(4.dp),
            )
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

// 头像：主题色圆形 + 人像图标。
@Composable
private fun MockAvatar() {
    Box(
        Modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        )
    }
}

// 流程箭头。
@Composable
private fun MockArrowDown() {
    Icon(
        Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}
