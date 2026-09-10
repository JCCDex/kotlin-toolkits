package com.jccdex.toolkits.core.text

/**
 * 通用字符串空值过滤（收敛各模块 `x.takeIf { it.isNotBlank() }` 样板）：
 * null / 空白返回 null，否则返回原值。
 */
fun String?.notBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }
