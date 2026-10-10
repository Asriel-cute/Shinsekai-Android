package com.dreemurr.shinsekai.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.defaultPopTransitionSpec
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigation3.ui.defaultTransitionSpec

// 跨 Tab 用轻量淡入淡出，同 Tab 保留 Nav3 默认转场。
internal class NavTransitionSpecs(
    val forward: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
    val pop: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
    val predictivePop: AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform,
)

internal fun navTransitionSpecs(
    tabOrder: List<NavKey>,
    backStacks: Map<NavKey, NavBackStack<NavKey>>,
): NavTransitionSpecs {
    val indexer = TabIndexer(tabOrder, backStacks)
    return NavTransitionSpecs(
        forward = { tabOrDefault(indexer, defaultTransitionSpec<NavKey>()) },
        pop = { tabOrDefault(indexer, defaultPopTransitionSpec<NavKey>()) },
        predictivePop = { swipeEdge ->
            tabOrDefault(indexer) {
                defaultPredictivePopTransitionSpec<NavKey>().invoke(this, swipeEdge)
            }
        },
    )
}

// 跨 Tab 不做全屏位移：目标页首帧需完成初次组合与排版，位移对掉帧最敏感；
// 统一 700ms 淡入淡出。
private fun AnimatedContentTransitionScope<Scene<NavKey>>.tabOrDefault(
    indexer: TabIndexer,
    default: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
): ContentTransform {
    val from = indexer.indexOf(initialState.key)
    val to = indexer.indexOf(targetState.key)
    if (from < 0 || to < 0 || from == to) return default(this)
    return fadeIn(tween(700)) togetherWith
        fadeOut(tween(700))
}

// Scene.key 是 NavEntry.contentKey，不是路由对象；子路由需反查所在栈。
private class TabIndexer(
    private val tabOrder: List<NavKey>,
    private val backStacks: Map<NavKey, NavBackStack<NavKey>>,
) {
    // 仅缓存固定根路由；动态返回栈不能缓存。
    private val tabSceneKeys = tabOrder.map { contentKeyOf(it) }

    fun indexOf(key: Any?): Int {
        val direct = tabSceneKeys.indexOfFirst { it == key }
        if (direct >= 0) return direct
        val owner = backStacks.entries.firstOrNull { (_, stack) ->
            stack.any { contentKeyOf(it) == key }
        }?.key ?: return -1
        return tabOrder.indexOf(owner)
    }
}

// 使用公开构造器，避免手写框架的 contentKey 规则。
private fun contentKeyOf(route: NavKey): Any = NavEntry(route) {}.contentKey
