#!/usr/bin/env python3
"""生成「前缀续写」联想词表（构建期执行一次，产物随 APK 打包）。

为什么是「前缀续写」：
  真实输入法的联想是「接着往下写」。例如上屏「你好」后，应该联想
  「你好吗 / 你好啊 / 你好坏 …」（即以已上屏内容为**前缀**的词），
  而不是「好啊 / 好的 / 好多」（以最后一个字开头、与前文无关的词）。

输出格式（assets/predict/lexicon.txt，UTF-8 文本）：
  P<TAB>首字<TAB>词1 词2 …     —— 单个字上屏后的续写
  W<TAB>前两字<TAB>词1 词2 …   —— 两字及以上上屏后的续写（只收 3 字词起）

用法：scripts/gen-predict-lexicon.py <cn_dicts 目录> <输出文件>
"""
import os
import sys
from collections import defaultdict

MAX_PER_KEY = 8
MIN_WEIGHT_SINGLE = 1
MIN_WEIGHT_PREFIX = 300
MIN_PREFIX_WORDS = 2


def is_pinyin(text: str) -> bool:
    if not text:
        return False
    for ch in text:
        if not (ch.isascii() and (ch.isalpha() or ch in " '")):
            return False
    return True


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    src_dir, out_path = sys.argv[1], sys.argv[2]

    single = defaultdict(dict)
    prefix = defaultdict(dict)

    files = scanned = 0
    for name in sorted(os.listdir(src_dir)):
        if not name.endswith(".dict.yaml"):
            continue
        files += 1
        with open(os.path.join(src_dir, name), "r", encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                line = line.rstrip("\n")
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) < 2:
                    continue
                word = parts[0].strip()
                if len(word) < 2 or len(word) > 6:
                    continue
                if any(ch.isspace() for ch in word):
                    continue
                if not is_pinyin(parts[1].strip()):
                    continue
                try:
                    weight = int(float(parts[2])) if len(parts) > 2 and parts[2].strip() else 0
                except ValueError:
                    weight = 0
                scanned += 1

                head = word[0]
                if weight >= MIN_WEIGHT_SINGLE:
                    prev = single[head].get(word)
                    if prev is None or weight > prev:
                        single[head][word] = weight

                if len(word) >= 3 and weight >= MIN_WEIGHT_PREFIX:
                    key = word[:2]
                    prev = prefix[key].get(word)
                    if prev is None or weight > prev:
                        prefix[key][word] = weight

    def top(words, limit):
        return [w for w, _ in sorted(words.items(), key=lambda kv: -kv[1])[:limit]]

    lines = []
    single_count = prefix_count = 0
    for head, words in sorted(single.items()):
        picked = top(words, MAX_PER_KEY)
        if picked:
            lines.append("P\t" + head + "\t" + " ".join(picked))
            single_count += len(picked)
    for key, words in sorted(prefix.items()):
        if len(words) < MIN_PREFIX_WORDS:
            continue
        picked = top(words, MAX_PER_KEY)
        lines.append("W\t" + key + "\t" + " ".join(picked))
        prefix_count += len(picked)

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    size = os.path.getsize(out_path)
    print(f"词典文件：{files} 个，扫描词条：{scanned}")
    print(f"单字索引：{len(single)} 键 / {single_count} 词")
    print(f"前缀索引：{len(prefix)} 键 / {prefix_count} 词")
    print(f"输出：{out_path}（{size / 1024:.1f} KB）")
    return 0


if __name__ == "__main__":
    sys.exit(main())