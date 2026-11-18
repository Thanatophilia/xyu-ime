package org.thanatophilia.xyuime.ui

/**
 * D5 symbol page data: three groups shown as a swiping grid.
 *
 * "What you see is what is inserted": [SymbolItem.text] is the exact committed character.
 * Keep names short: they are the accessibility labels, not visible captions.
 */
internal enum class SymbolGroup(val key: String, val title: String) {
    ChinesePunctuation("cn", "中文标点"),
    EnglishSymbols("en", "英文符号"),
    MathUnits("math", "数学单位"),
}

internal data class SymbolItem(val text: String, val name: String)

internal object SymbolLayout {
    /** 27 common Chinese punctuation marks, in reading order. */
    val chinesePunctuation = listOf(
        SymbolItem("，", "逗号"), SymbolItem("。", "句号"), SymbolItem("、", "顿号"),
        SymbolItem("；", "分号"), SymbolItem("：", "冒号"), SymbolItem("！", "叹号"),
        SymbolItem("？", "问号"), SymbolItem("“", "左双引号"), SymbolItem("”", "右双引号"),
        SymbolItem("‘", "左单引号"), SymbolItem("’", "右单引号"), SymbolItem("（", "左括号"),
        SymbolItem("）", "右括号"), SymbolItem("《", "书名号左"), SymbolItem("》", "书名号右"),
        SymbolItem("【", "方头括号左"), SymbolItem("】", "方头括号右"), SymbolItem("「", "直角引号左"),
        SymbolItem("」", "直角引号右"), SymbolItem("〈", "单书名号左"), SymbolItem("〉", "单书名号右"),
        SymbolItem("『", "白角引号左"), SymbolItem("』", "白角引号右"), SymbolItem("…", "省略号"),
        SymbolItem("—", "破折号"), SymbolItem("～", "波浪号"), SymbolItem("·", "间隔号"),
    )

    /** 26 ASCII symbols. All entries must stay within U+0020..U+007E. */
    val englishSymbols = listOf(
        SymbolItem("!", "叹号"), SymbolItem("@", "at 符号"), SymbolItem("#", "井号"),
        SymbolItem("$", "美元号"), SymbolItem("%", "百分号"), SymbolItem("^", "脱字符"),
        SymbolItem("&", "与号"), SymbolItem("*", "星号"), SymbolItem("(", "左圆括号"),
        SymbolItem(")", "右圆括号"), SymbolItem("-", "连字符"), SymbolItem("_", "下划线"),
        SymbolItem("=", "等号"), SymbolItem("+", "加号"), SymbolItem("/", "斜杠"),
        SymbolItem("\\", "反斜杠"), SymbolItem("'", "单引号"), SymbolItem("\"", "双引号"),
        SymbolItem("?", "半角问号"), SymbolItem("~", "波浪线"), SymbolItem("{", "左花括号"),
        SymbolItem("}", "右花括号"), SymbolItem("[", "左方括号"), SymbolItem("]", "右方括号"),
        SymbolItem("|", "竖线"), SymbolItem("`", "反引号"),
    )

    /** 22 math and unit symbols. Minus is U+2212, multiplication U+00D7, division U+00F7. */
    val mathUnits = listOf(
        SymbolItem("+", "加"), SymbolItem("−", "减"), SymbolItem("×", "乘"),
        SymbolItem("÷", "除"), SymbolItem("=", "等号"), SymbolItem("≠", "不等于"),
        SymbolItem("≈", "约等于"), SymbolItem("≤", "小于等于"), SymbolItem("≥", "大于等于"),
        SymbolItem("±", "正负"), SymbolItem("%", "百分号"), SymbolItem("‰", "千分号"),
        SymbolItem("°", "度"), SymbolItem("℃", "摄氏度"), SymbolItem("€", "欧元"),
        SymbolItem("¥", "日元或人民币"), SymbolItem("£", "英镑"), SymbolItem("$", "美元"),
        SymbolItem("√", "根号"), SymbolItem("∞", "无穷"), SymbolItem("π", "圆周率"),
        SymbolItem("µ", "微符号"),
    )

    val groups = SymbolGroup.entries.toList()

    fun items(group: SymbolGroup): List<SymbolItem> = when (group) {
        SymbolGroup.ChinesePunctuation -> chinesePunctuation
        SymbolGroup.EnglishSymbols -> englishSymbols
        SymbolGroup.MathUnits -> mathUnits
    }

    /** ASCII-only editors open on the English group; ordinary text opens on Chinese punctuation. */
    fun initialGroup(asciiEditor: Boolean): SymbolGroup =
        if (asciiEditor) SymbolGroup.EnglishSymbols else SymbolGroup.ChinesePunctuation

    /** Swipe mapping used by the page: forward = next group, backward = previous, both cyclic. */
    fun next(group: SymbolGroup, forward: Boolean): SymbolGroup {
        val index = groups.indexOf(group)
        val next = if (forward) (index + 1) % groups.size else (index + groups.size - 1) % groups.size
        return groups[next]
    }
}
