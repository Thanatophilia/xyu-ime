package org.thanatophilia.xyuime.ui

/**
 * D5/D5.1 symbol page data: four groups shown as a swiping grid. Common punctuation stays in
 * the first three groups; less frequent directional, scientific and typographic symbols live
 * in [SymbolGroup.ExtendedSymbols], following the layered approach used by open-source IMEs.
 *
 * "What you see is what is inserted": [SymbolItem.text] is the exact committed character.
 * Keep names short: they are the accessibility labels, not visible captions.
 */
internal enum class SymbolGroup(val key: String, val title: String) {
    ChinesePunctuation("cn", "中文标点"),
    EnglishSymbols("en", "英文符号"),
    MathUnits("math", "数学单位"),
    ExtendedSymbols("more", "扩展符号"),
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

    /** 30 printable ASCII symbols. Comma and period stay on the bottom row because Chinese
     * mode routes them through the candidate-first punctuation path.
     */
    val englishSymbols = listOf(
        SymbolItem("!", "叹号"), SymbolItem("@", "at 符号"), SymbolItem("#", "井号"),
        SymbolItem("$", "美元号"), SymbolItem("%", "百分号"), SymbolItem("^", "脱字符"),
        SymbolItem("&", "与号"), SymbolItem("*", "星号"), SymbolItem("(", "左圆括号"),
        SymbolItem(")", "右圆括号"), SymbolItem("-", "连字符"), SymbolItem("_", "下划线"),
        SymbolItem("=", "等号"), SymbolItem("+", "加号"), SymbolItem("/", "斜杠"),
        SymbolItem("\\", "反斜杠"), SymbolItem("'", "单引号"), SymbolItem("\"", "双引号"),
        SymbolItem(",", "半角逗号"), SymbolItem(".", "半角句号"),
        SymbolItem(":", "半角冒号"), SymbolItem(";", "半角分号"),
        SymbolItem("<", "小于号"), SymbolItem(">", "大于号"),
        SymbolItem("?", "半角问号"), SymbolItem("~", "波浪线"),
        SymbolItem("{", "左花括号"), SymbolItem("}", "右花括号"),
        SymbolItem("[", "左方括号"), SymbolItem("]", "右方括号"),
        SymbolItem("|", "竖线"), SymbolItem("`", "反引号"),
    )

    /** 93 less frequent directional, scientific, typographic and publication symbols. */
    val extendedSymbols = listOf(
        SymbolItem("↑", "上箭头"), SymbolItem("↓", "下箭头"),
        SymbolItem("←", "左箭头"), SymbolItem("→", "右箭头"),
        SymbolItem("↔", "左右箭头"), SymbolItem("↕", "上下箭头"),
        SymbolItem("↖", "左上箭头"), SymbolItem("↗", "右上箭头"),
        SymbolItem("↘", "右下箭头"), SymbolItem("↙", "左下箭头"),
        SymbolItem("α", "小写阿尔法"), SymbolItem("β", "小写贝塔"),
        SymbolItem("γ", "小写伽马"), SymbolItem("δ", "小写德尔塔"),
        SymbolItem("θ", "小写西塔"), SymbolItem("λ", "小写拉姆达"),
        SymbolItem("μ", "小写缪"), SymbolItem("Ω", "大写欧米伽"),
        SymbolItem("∑", "求和"), SymbolItem("∏", "连乘"),
        SymbolItem("∫", "积分"), SymbolItem("∂", "偏微分"),
        SymbolItem("∇", "梯度"), SymbolItem("∝", "正比"),
        SymbolItem("∴", "所以"), SymbolItem("∵", "因为"),
        SymbolItem("∈", "属于"), SymbolItem("∉", "不属于"),
        SymbolItem("∩", "交集"), SymbolItem("∪", "并集"),
        SymbolItem("≡", "恒等"), SymbolItem("⊂", "真子集"),
        SymbolItem("⊆", "子集"), SymbolItem("⊃", "真超集"),
        SymbolItem("⊇", "超集"), SymbolItem("½", "二分之一"),
        SymbolItem("⅓", "三分之一"), SymbolItem("¼", "四分之一"),
        SymbolItem("¾", "四分之三"), SymbolItem("⁰", "上标零"),
        SymbolItem("¹", "上标一"), SymbolItem("²", "上标二"),
        SymbolItem("³", "上标三"), SymbolItem("₀", "下标零"),
        SymbolItem("₁", "下标一"), SymbolItem("₂", "下标二"),
        SymbolItem("₃", "下标三"), SymbolItem("₹", "印度卢比"),
        SymbolItem("₩", "韩元"), SymbolItem("₽", "俄罗斯卢布"),
        SymbolItem("₫", "越南盾"), SymbolItem("₺", "土耳其里拉"),
        SymbolItem("℉", "华氏度"), SymbolItem("ℓ", "升"),
        SymbolItem("Å", "埃"), SymbolItem("‰", "千分号"),
        SymbolItem("℅", "百分比符号"), SymbolItem("⁄", "分数线"),
        SymbolItem("•", "项目符号"), SymbolItem("‥", "二点省略号"),
        SymbolItem("⋯", "中间省略号"), SymbolItem("‽", "反问叹号"),
        SymbolItem("¡", "倒叹号"), SymbolItem("¿", "倒问号"),
        SymbolItem("„", "低位双引号左"), SymbolItem("‚", "低位单引号左"),
        SymbolItem("‹", "单尖引号左"), SymbolItem("›", "单尖引号右"),
        SymbolItem("«", "双尖引号左"), SymbolItem("»", "双尖引号右"),
        SymbolItem("※", "参考符号"), SymbolItem("〃", "同上符号"),
        SymbolItem("々", "叠字符号"), SymbolItem("〆", "和文结束符"),
        SymbolItem("〒", "邮政符号"), SymbolItem("〇", "汉字零"),
        SymbolItem("†", "剑标"), SymbolItem("‡", "双剑标"),
        SymbolItem("§", "章节符"), SymbolItem("¶", "段落符"),
        SymbolItem("№", "序号符"), SymbolItem("©", "版权"),
        SymbolItem("®", "注册商标"), SymbolItem("™", "商标"),
        SymbolItem("✓", "对勾"), SymbolItem("★", "实心星"),
        SymbolItem("☆", "空心星"), SymbolItem("♥", "红桃"),
        SymbolItem("♠", "黑桃"), SymbolItem("♦", "方块"),
        SymbolItem("♣", "梅花"), SymbolItem("♪", "八分音符"),
        SymbolItem("♫", "双八分音符"),
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
        SymbolGroup.ExtendedSymbols -> extendedSymbols
    }

    /** ASCII-only editors open on the English group; ordinary text opens on Chinese punctuation. */
    fun initialGroup(asciiEditor: Boolean): SymbolGroup =
        if (asciiEditor) SymbolGroup.EnglishSymbols else SymbolGroup.ChinesePunctuation

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
