package com.zsz.studyassistant.ui

/**
 * 列表预览用的「轻量排版」：把 LaTeX / Markdown 转成**可读的纯文本**。
 *
 * 为什么不用 WebView 渲染：复习/错题本列表一屏可能有几十条，
 * 每条挂一个 WebView（加载 KaTeX）内存与滚动都会明显变卡。
 * 这里用符号替换把常见的公式命令转成 Unicode（∫ ∮ ∑ × · ≤ √ ρ ε …），
 * 让预览「一眼能读」，真正的精确排版仍由对话页的 KaTeX 负责。
 */
object TextPretty {

    private val GREEK = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
        "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω"
    )

    private val SYMBOLS = mapOf(
        "oint" to "∮", "int" to "∫", "iint" to "∬", "iiint" to "∭", "sum" to "∑", "prod" to "∏",
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "neq" to "≠", "approx" to "≈",
        "equiv" to "≡", "propto" to "∝", "infty" to "∞", "partial" to "∂", "nabla" to "∇",
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftrightarrow" to "⇔",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "cup" to "∪", "cap" to "∩",
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°", "circ" to "∘",
        "ldots" to "…", "cdots" to "⋯", "quad" to " ", "qquad" to "  ", "left" to "", "right" to ""
    )

    fun of(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s: String = raw

        // 1) 去掉 Markdown 强调/标题/引用等标记
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^\\s*>\\s?"), "")
        s = s.replace(Regex("`([^`]+)`"), "$1")

        // 2) 去掉数学定界符（\( \) \[ \] $ $$）
        s = s.replace("\\(", "").replace("\\)", "")
        s = s.replace("\\[", "").replace("\\]", "")
        s = s.replace("$$", "").replace("$", "")

        // 3) \frac{a}{b} → (a)/(b)；\sqrt{x} → √(x)（支持一层花括号）
        s = s.replace(Regex("\\\\frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}"), "($1)/($2)")
        s = s.replace(Regex("\\\\dfrac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}"), "($1)/($2)")
        s = s.replace(Regex("\\\\tfrac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}"), "($1)/($2)")
        s = s.replace(Regex("\\\\sqrt\\s*\\{([^{}]*)\\}"), "√($1)")
        s = s.replace(Regex("\\\\text\\s*\\{([^{}]*)\\}"), "$1")
        s = s.replace(Regex("\\\\mathrm\\s*\\{([^{}]*)\\}"), "$1")
        s = s.replace(Regex("\\\\operatorname\\s*\\{([^{}]*)\\}"), "$1")
        // \vec{a} / \hat{a} / \bar{a} / \tilde{a} → 直接取内容
        s = s.replace(Regex("\\\\(?:vec|hat|bar|tilde|dot|overline|underline)\\s*\\{([^{}]*)\\}"), "$1")

        // 4) 常见希腊字母与符号
        for ((k, v) in SYMBOLS) s = s.replace("\\$k", v)
        for ((k, v) in GREEK) s = s.replace("\\$k", v)

        // 5) 剩余的命令与转义：去掉反斜杠，间距命令变空格
        s = s.replace(Regex("\\\\[,;:!]"), " ")
        s = s.replace(Regex("\\\\ "), " ")
        s = s.replace("\\\\", " ")
        s = s.replace(Regex("\\\\([A-Za-z]+)"), "$1")   // \leftover → leftover
        s = s.replace("{", "").replace("}", "")

        // 6) 收拾空白
        s = s.replace(Regex("[ \\t]{2,}"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    /** 单行预览：压掉换行，截断到 [max] 个字符 */
    fun oneLine(raw: String?, max: Int = 90): String {
        val s = of(raw).replace(Regex("\\s+"), " ").trim()
        return if (s.length <= max) s else s.take(max) + "…"
    }
}
