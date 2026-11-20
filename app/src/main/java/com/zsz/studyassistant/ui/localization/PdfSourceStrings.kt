package com.zsz.studyassistant.ui

internal val PDF_SOURCE_ZH = mapOf(
    "pdfSource.find" to "查找 PDF 原文",
    "pdfSource.close" to "关闭",
    "pdfSource.cancel" to "取消查找",
    "pdfSource.documentProgress" to "文档 {n}/{total} · {name}",
    "pdfSource.verifying" to "正在校验原件并读取页数…",
    "pdfSource.pageProgress" to "已检查 {done}/{total} 页（{percent}%）· 原件第 {page} 页",
    "pdfSource.title" to "查找 PDF 原文",
    "pdfSource.hint" to "在本轮附件的已选页中查找完整选文，仅忽略排版空白。命中代表原文相同，不代表答案已核实。页码为 PDF 原件页序。",
    "pdfSource.noMatch" to "未找到完整选文。改写、公式或扫描页可能无法匹配；可缩短选文后重试。",
    "pdfSource.queryInvalid" to "请选择 8～2000 个字符的原文片段，至少包含 4 个文字或数字。",
    "pdfSource.tooLarge" to "PDF 文字量超出本地查找上限，无法完成全部检查。请减少所选页范围。",
    "pdfSource.tooManyMatches" to "匹配页面超过 100 页，请选择更长、更具体的原文片段。",
    "pdfSource.page" to "{name} · 原件第 {n} 页",
    "pdfSource.results" to "返回匹配列表"
)
internal val PDF_SOURCE_EN = mapOf(
    "pdfSource.find" to "Find text in PDF",
    "pdfSource.close" to "Close",
    "pdfSource.cancel" to "Cancel search",
    "pdfSource.documentProgress" to "Document {n}/{total} · {name}",
    "pdfSource.verifying" to "Verifying the original and reading its page count…",
    "pdfSource.pageProgress" to "Checked {done}/{total} pages ({percent}%) · Original page {page}",
    "pdfSource.title" to "Find text in PDF",
    "pdfSource.hint" to "Find the full selection in this turn’s selected PDF pages, ignoring layout whitespace only. A match confirms the text, not the answer. Page numbers refer to the original PDF.",
    "pdfSource.noMatch" to "No exact match found. Paraphrases, formulas or scanned pages may not match. Try a shorter excerpt.",
    "pdfSource.queryInvalid" to "Select 8–2,000 characters, including at least 4 letters or digits.",
    "pdfSource.tooLarge" to "The PDF exceeds the local text search limit. The full search could not finish. Select fewer pages.",
    "pdfSource.tooManyMatches" to "More than 100 pages matched. Select a longer, more specific excerpt.",
    "pdfSource.page" to "{name} · Original page {n}",
    "pdfSource.results" to "Back to matches"
)
internal val PDF_SOURCE_JA = mapOf(
    "pdfSource.find" to "PDF 原文を検索",
    "pdfSource.close" to "閉じる",
    "pdfSource.cancel" to "検索をキャンセル",
    "pdfSource.documentProgress" to "文書 {n}/{total} · {name}",
    "pdfSource.verifying" to "原本を検証し、ページ数を読み込んでいます…",
    "pdfSource.pageProgress" to "{done}/{total} ページを確認（{percent}%）· 原本の {page} ページ",
    "pdfSource.title" to "PDF 原文を検索",
    "pdfSource.hint" to "今回の添付 PDF の選択ページ内で、レイアウト上の空白のみを除いて選択文全体を検索します。一致は原文の確認であり、回答の正しさを保証しません。ページ番号は元の PDF の順番です。",
    "pdfSource.noMatch" to "完全に一致する原文がありません。言い換え・数式・スキャンページは一致しない場合があります。短い原文で再試行してください。",
    "pdfSource.queryInvalid" to "文字または数字を 4 文字以上含む、8～2000 文字の原文を選択してください。",
    "pdfSource.tooLarge" to "PDF の文字量が端末内検索の上限を超え、全体を確認できませんでした。選択ページ数を減らしてください。",
    "pdfSource.tooManyMatches" to "100 ページを超える一致があります。より長く具体的な原文を選択してください。",
    "pdfSource.page" to "{name} · 元の PDF の {n} ページ",
    "pdfSource.results" to "一致一覧に戻る"
)
internal val PDF_SOURCE_KO = mapOf(
    "pdfSource.find" to "PDF 원문 찾기",
    "pdfSource.close" to "닫기",
    "pdfSource.cancel" to "검색 취소",
    "pdfSource.documentProgress" to "문서 {n}/{total} · {name}",
    "pdfSource.verifying" to "원본을 검증하고 페이지 수를 읽는 중…",
    "pdfSource.pageProgress" to "{done}/{total}페이지 확인 ({percent}%) · 원본 {page}페이지",
    "pdfSource.title" to "PDF 원문 찾기",
    "pdfSource.hint" to "이번 요청의 PDF 선택 페이지에서 배치 공백만 무시하고 선택한 전체 문장을 찾습니다. 일치는 원문 확인이며 답의 정확성을 보장하지 않습니다. 페이지 번호는 원본 PDF 순서입니다.",
    "pdfSource.noMatch" to "정확히 일치하는 원문이 없습니다. 바꿔 쓴 문장, 수식 또는 스캔 페이지는 일치하지 않을 수 있습니다. 더 짧은 원문으로 다시 시도하세요.",
    "pdfSource.queryInvalid" to "문자 또는 숫자 4개 이상을 포함하여 8~2000자의 원문을 선택하세요.",
    "pdfSource.tooLarge" to "PDF 텍스트가 로컬 검색 한도를 초과하여 전체 검사를 완료하지 못했습니다. 선택 페이지 수를 줄이세요.",
    "pdfSource.tooManyMatches" to "100페이지 넘게 일치합니다. 더 길고 구체적인 원문을 선택하세요.",
    "pdfSource.page" to "{name} · 원본 {n}페이지",
    "pdfSource.results" to "일치 목록으로 돌아가기"
)
