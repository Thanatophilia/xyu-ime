package org.thanatophilia.xyuime.ui

/**
 * Symbol page data. Groups: Common, Recent, Chinese punctuation, English symbols, Math,
 * Arrows, Ordinal, Scripts. "Recent" is populated at runtime from [SymbolRecentStore];
 * all other groups are static and "what you see is what is inserted".
 */
internal enum class SymbolGroup(val key: String, val title: String) {
    Common("common", "常用"),
    Recent("recent", "最近"),
    ChinesePunctuation("cn", "中"),
    EnglishSymbols("en", "英"),
    MathUnits("math", "数"),
    Arrows("arrows", "箭头"),
    Ordinal("ordinal", "序号"),
    Scripts("marks", "角标"),
}

internal data class SymbolItem(val text: String, val name: String)

internal object SymbolLayout {
    /** 35 daily-use characters: Chinese punctuation, common ASCII and units mixed. */
    val commonSymbols = listOf(
        SymbolItem("，", "逗号"), SymbolItem("。", "句号"), SymbolItem("、", "顿号"),
        SymbolItem("？", "问号"), SymbolItem("！", "叹号"), SymbolItem("：", "冒号"),
        SymbolItem("；", "分号"), SymbolItem("“", "左双引号"), SymbolItem("”", "右双引号"),
        SymbolItem("‘", "左单引号"), SymbolItem("’", "右单引号"), SymbolItem("（", "左括号"),
        SymbolItem("）", "右括号"), SymbolItem("《", "书名号左"), SymbolItem("》", "书名号右"),
        SymbolItem("—", "破折号"), SymbolItem("…", "省略号"), SymbolItem("·", "间隔号"),
        SymbolItem("@", "at 符号"), SymbolItem("#", "井号"), SymbolItem("$", "美元号"),
        SymbolItem("%", "百分号"), SymbolItem("&", "与号"), SymbolItem("*", "星号"),
        SymbolItem("+", "加号"), SymbolItem("-", "连字符"), SymbolItem("/", "斜杠"),
        SymbolItem("\\", "反斜杠"), SymbolItem("'", "单引号"), SymbolItem("\"", "双引号"),
        SymbolItem("?", "半角问号"), SymbolItem("=", "等号"), SymbolItem("°", "度"),
        SymbolItem("℃", "摄氏度"), SymbolItem("✓", "对勾"),
    )

    /** 33 Chinese punctuation and CJK marks. */
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
        SymbolItem("※", "参考符号"), SymbolItem("〃", "同上符号"), SymbolItem("々", "叠字符号"),
        SymbolItem("〆", "和文结束符"), SymbolItem("〒", "邮政符号"), SymbolItem("〇", "汉字零"),
    )

    /** 52 English/western symbols, including typographic quotes and publication marks. */
    val englishSymbols = listOf(
        SymbolItem("!", "叹号"), SymbolItem("@", "at 符号"), SymbolItem("#", "井号"),
        SymbolItem("$", "美元号"), SymbolItem("%", "百分号"), SymbolItem("^", "脱字符"),
        SymbolItem("&", "与号"), SymbolItem("*", "星号"), SymbolItem("(", "左圆括号"),
        SymbolItem(")", "右圆括号"), SymbolItem("-", "连字符"), SymbolItem("_", "下划线"),
        SymbolItem("=", "等号"), SymbolItem("+", "加号"), SymbolItem("/", "斜杠"),
        SymbolItem("\\", "反斜杠"), SymbolItem("'", "单引号"), SymbolItem("\"", "双引号"),
        SymbolItem(":", "半角冒号"), SymbolItem(";", "半角分号"),
        SymbolItem("<", "小于号"), SymbolItem(">", "大于号"),
        SymbolItem("?", "半角问号"), SymbolItem("~", "波浪线"),
        SymbolItem("{", "左花括号"), SymbolItem("}", "右花括号"),
        SymbolItem("[", "左方括号"), SymbolItem("]", "右方括号"),
        SymbolItem("|", "竖线"), SymbolItem("`", "反引号"),
        SymbolItem("„", "低位双引号左"), SymbolItem("‚", "低位单引号左"),
        SymbolItem("‹", "单尖引号左"), SymbolItem("›", "单尖引号右"),
        SymbolItem("«", "双尖引号左"), SymbolItem("»", "双尖引号右"),
        SymbolItem("¡", "倒叹号"), SymbolItem("¿", "倒问号"),
        SymbolItem("©", "版权"), SymbolItem("®", "注册商标"), SymbolItem("™", "商标"),
        SymbolItem("№", "序号符"), SymbolItem("§", "章节符"), SymbolItem("¶", "段落符"),
        SymbolItem("†", "剑标"), SymbolItem("‡", "双剑标"),
        SymbolItem("℅", "百分比符号"), SymbolItem("⁄", "分数线"),
        SymbolItem("•", "项目符号"), SymbolItem("‥", "二点省略号"),
        SymbolItem("⋯", "中间省略号"), SymbolItem("‽", "反问叹号"),
    )

    /** 59 math, Greek, fractions, currency and unit symbols. */
    val mathUnits = listOf(
        SymbolItem("+", "加"), SymbolItem("−", "减"), SymbolItem("×", "乘"),
        SymbolItem("÷", "除"), SymbolItem("=", "等号"), SymbolItem("≠", "不等于"),
        SymbolItem("≈", "约等于"), SymbolItem("≤", "小于等于"), SymbolItem("≥", "大于等于"),
        SymbolItem("±", "正负"), SymbolItem("%", "百分号"), SymbolItem("‰", "千分号"),
        SymbolItem("°", "度"), SymbolItem("℃", "摄氏度"), SymbolItem("€", "欧元"),
        SymbolItem("¥", "日元或人民币"), SymbolItem("£", "英镑"), SymbolItem("$", "美元"),
        SymbolItem("√", "根号"), SymbolItem("∞", "无穷"), SymbolItem("π", "圆周率"),
        SymbolItem("µ", "微符号"),
        SymbolItem("α", "小写阿尔法"), SymbolItem("β", "小写贝塔"),
        SymbolItem("γ", "小写伽马"), SymbolItem("δ", "小写德尔塔"),
        SymbolItem("θ", "小写西塔"), SymbolItem("λ", "小写拉姆达"),
        SymbolItem("μ", "小写缪"), SymbolItem("Ω", "大写欧米伽"),
        SymbolItem("∑", "求和"), SymbolItem("∏", "连乘"), SymbolItem("∫", "积分"),
        SymbolItem("∂", "偏微分"), SymbolItem("∇", "梯度"), SymbolItem("∝", "正比"),
        SymbolItem("∴", "所以"), SymbolItem("∵", "因为"),
        SymbolItem("∈", "属于"), SymbolItem("∉", "不属于"),
        SymbolItem("∩", "交集"), SymbolItem("∪", "并集"), SymbolItem("≡", "恒等"),
        SymbolItem("⊂", "真子集"), SymbolItem("⊆", "子集"), SymbolItem("⊃", "真超集"),
        SymbolItem("⊇", "超集"), SymbolItem("½", "二分之一"), SymbolItem("⅓", "三分之一"),
        SymbolItem("¼", "四分之一"), SymbolItem("¾", "四分之三"),
        SymbolItem("₹", "印度卢比"), SymbolItem("₩", "韩元"), SymbolItem("₽", "俄罗斯卢布"),
        SymbolItem("₫", "越南盾"), SymbolItem("₺", "土耳其里拉"),
        SymbolItem("℉", "华氏度"), SymbolItem("ℓ", "升"), SymbolItem("Å", "埃"),
    )

    /** 18 directional arrows. */
    val arrows = listOf(
        SymbolItem("↑", "上箭头"), SymbolItem("↓", "下箭头"),
        SymbolItem("←", "左箭头"), SymbolItem("→", "右箭头"),
        SymbolItem("↔", "左右箭头"), SymbolItem("↕", "上下箭头"),
        SymbolItem("↖", "左上箭头"), SymbolItem("↗", "右上箭头"),
        SymbolItem("↘", "右下箭头"), SymbolItem("↙", "左下箭头"),
        SymbolItem("⇐", "左双箭头"), SymbolItem("⇒", "右双箭头"),
        SymbolItem("⇑", "上双箭头"), SymbolItem("⇓", "下双箭头"),
        SymbolItem("⇦", "左白箭头"), SymbolItem("⇧", "上白箭头"),
        SymbolItem("⇨", "右白箭头"), SymbolItem("⇩", "下白箭头"),
    )

    /** 30 ordinal/numbering symbols. */
    val ordinal = listOf(
        SymbolItem("①", "圈一"), SymbolItem("②", "圈二"), SymbolItem("③", "圈三"),
        SymbolItem("④", "圈四"), SymbolItem("⑤", "圈五"), SymbolItem("⑥", "圈六"),
        SymbolItem("⑦", "圈七"), SymbolItem("⑧", "圈八"), SymbolItem("⑨", "圈九"),
        SymbolItem("⑩", "圈十"), SymbolItem("⑪", "圈十一"), SymbolItem("⑫", "圈十二"),
        SymbolItem("⑬", "圈十三"), SymbolItem("⑭", "圈十四"), SymbolItem("⑮", "圈十五"),
        SymbolItem("⑯", "圈十六"), SymbolItem("⑰", "圈十七"), SymbolItem("⑱", "圈十八"),
        SymbolItem("⑲", "圈十九"), SymbolItem("⑳", "圈二十"),
        SymbolItem("⑴", "括号一"), SymbolItem("⑵", "括号二"), SymbolItem("⑶", "括号三"),
        SymbolItem("⑷", "括号四"), SymbolItem("⑸", "括号五"),
        SymbolItem("㈠", "汉字一"), SymbolItem("㈡", "汉字二"), SymbolItem("㈢", "汉字三"),
        SymbolItem("㈣", "汉字四"), SymbolItem("㈤", "汉字五"),
    )

    /** 31 superscript/subscript characters. */
    val scripts = listOf(
        SymbolItem("⁰", "上标零"), SymbolItem("¹", "上标一"), SymbolItem("²", "上标二"),
        SymbolItem("³", "上标三"), SymbolItem("⁴", "上标四"), SymbolItem("⁵", "上标五"),
        SymbolItem("⁶", "上标六"), SymbolItem("⁷", "上标七"), SymbolItem("⁸", "上标八"),
        SymbolItem("⁹", "上标九"), SymbolItem("⁺", "上标加"), SymbolItem("⁻", "上标减"),
        SymbolItem("⁼", "上标等号"), SymbolItem("⁽", "上标左括号"), SymbolItem("⁾", "上标右括号"),
        SymbolItem("ⁿ", "上标n"), SymbolItem("₀", "下标零"), SymbolItem("₁", "下标一"),
        SymbolItem("₂", "下标二"), SymbolItem("₃", "下标三"), SymbolItem("₄", "下标四"),
        SymbolItem("₅", "下标五"), SymbolItem("₆", "下标六"), SymbolItem("₇", "下标七"),
        SymbolItem("₈", "下标八"), SymbolItem("₉", "下标九"), SymbolItem("₊", "下标加"),
        SymbolItem("₋", "下标减"), SymbolItem("₌", "下标等号"),
        SymbolItem("₍", "下标左括号"), SymbolItem("₎", "下标右括号"),
    )

    val groups = SymbolGroup.entries.toList()

    private val staticItems = mapOf(
        SymbolGroup.Common to commonSymbols,
        SymbolGroup.ChinesePunctuation to chinesePunctuation,
        SymbolGroup.EnglishSymbols to englishSymbols,
        SymbolGroup.MathUnits to mathUnits,
        SymbolGroup.Arrows to arrows,
        SymbolGroup.Ordinal to ordinal,
        SymbolGroup.Scripts to scripts,
    )

    fun items(group: SymbolGroup): List<SymbolItem> = staticItems[group].orEmpty()

    private val namesByText by lazy {
        (staticItems.values.flatten() + commonSymbols).associate { it.text to it.name }
    }

    fun nameOf(text: String): String = namesByText[text] ?: text

    fun recentItems(texts: List<String>): List<SymbolItem> = texts.map { SymbolItem(it, nameOf(it)) }

    /** ASCII-only editors open on English; ordinary text opens on the curated common group. */
    fun initialGroup(asciiEditor: Boolean): SymbolGroup =
        if (asciiEditor) SymbolGroup.EnglishSymbols else SymbolGroup.Common

    private const val MIDDLE_CYCLE = 1000
    val pageCount: Int get() = groups.size * (MIDDLE_CYCLE * 2 + 1)

    fun groupAtPage(page: Int): SymbolGroup = groups[page % groups.size]

    fun middlePage(group: SymbolGroup): Int = groups.size * MIDDLE_CYCLE + group.ordinal

    fun isNearPageLimit(page: Int): Boolean = page < groups.size || page >= pageCount - groups.size

    /** Tabs animate to the nearest copy of their group, including across the cycle boundary. */
    fun nearestPage(page: Int, group: SymbolGroup): Int {
        val count = groups.size
        val forward = (group.ordinal - page % count + count) % count
        val nearest = page + if (forward <= count / 2) forward else forward - count
        return when {
            nearest < 0 -> nearest + count
            nearest >= pageCount -> nearest - count
            else -> nearest
        }
    }
}
