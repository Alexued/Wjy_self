#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 qa_md 文件中提取结构化题库数据，输出为 JSON。
用法: python extract_bank.py [--pilot]  (pilot 只处理逻辑判断)
"""

import os
import re
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

QA_ROOT = os.path.join(os.path.dirname(__file__), 'qa_md')
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), 'question_bank')

# 子类型标签 -> 短码（用于ID）
SUBTYPE_CODE = {
    '削弱题型': 'xr',
    '加强选非题': 'jqxf',
    '加强其他': 'jqqt',
    '加强题型': 'jq',
    '搭桥': 'dq',
    '补充论据': 'bcly',
    '常规翻译': 'cfy',
    '必要条件': 'bytj',
    '推理形式': 'tlxs',
    '日常结论': 'rcjl',
    '真假推理': 'zjtl',
    '组合排列': 'zhpl',
    '原因解释': 'yyjs',
    '论证结构': 'lzjg',
    '论证缺陷': 'lzqx',
    '集合推理': 'jhtl',
    '翻译推理其他': 'fwtl',
}

# 子类型标签映射：从文件名提取
SUBTYPE_MAP = {
    '削弱题型': '削弱题型',
    '加强选非题': '加强选非题',
    '加强-其他': '加强其他',
    '加强题型': '加强题型',
    '搭桥': '搭桥',
    '补充论据': '补充论据',
    '常规翻译': '常规翻译',
    '必要条件': '必要条件',
    '推理形式': '推理形式',
    '日常结论': '日常结论',
    '真假推理': '真假推理',
    '组合排列': '组合排列',
    '组合排列-单题': '组合排列',
    '原因解释': '原因解释',
    '论证结构': '论证结构',
    '论证缺陷': '论证缺陷',
    '集合推理': '集合推理',
    '翻译推理-其他': '翻译推理其他',
}

# ID 前缀映射
ID_PREFIX = {
    '逻辑填空': 'yx_ltj',
    '语句表达': 'yx_yjbd',
    '阅读理解': 'yx_ydlj',
    '类比推理': 'pb_lbtl',
    '定义判断': 'pb_dydp',
    '逻辑判断': 'pb_ljpd',
}


def extract_subtype_from_filename(filename):
    """从文件名提取子类型标签"""
    name = filename.replace('.md', '')
    for prefix in ['逻辑判断_', '类比推理_', '定义判断_', '逻辑填空_', '语句表达_', '阅读理解_']:
        name = name.replace(prefix, '')
    # 按长度倒序匹配，避免 "加强题型" 匹配到 "加强选非题" 的前缀
    for key in sorted(SUBTYPE_MAP.keys(), key=len, reverse=True):
        if key in name:
            return SUBTYPE_MAP[key]
    return None


def extract_group_number(filename):
    """从文件名提取组号"""
    m = re.search(r'第(\d+)组', filename)
    if m:
        return int(m.group(1))
    m = re.search(r'(\d+)\.md$', filename)
    if m:
        return int(m.group(1))
    return 0


def parse_options_from_lines(lines):
    """从多行文本中解析选项，支持单行多选项和多行单选项"""
    options = []
    for line in lines:
        stripped = line.strip()
        if not stripped:
            continue
        # 尝试匹配单行多选项: "A.xxx B.xxx C.xxx D.xxx"
        multi = re.findall(r'([A-D])[\.\．、]([^A-D]*?)(?=[A-D][\.\．、]|$)', stripped)
        if len(multi) >= 2:
            for label, text in multi:
                text = text.strip()
                if text:
                    options.append(f"{label}. {text}")
        # 单行单选项
        elif re.match(r'^[A-D][\.\．、\s]', stripped):
            options.append(stripped)
    return options


def parse_question_block(block, subtype_tag=None):
    """解析单个题目块"""
    lines = block.strip().split('\n')
    if not lines:
        return None

    # 提取题号和答案: ## N. 【答案】X
    header_match = re.match(r'##\s*(\d+)\.\s*【答案】([A-D])', lines[0].strip())
    if not header_match:
        return None

    q_no = int(header_match.group(1))
    answer = header_match.group(2)

    # 分割：题干 / 选项 / 解析
    stem_lines = []
    option_lines = []
    analysis_lines = []

    section = 'stem'
    for line in lines[1:]:
        stripped = line.strip()

        # 检测解析开始
        if stripped.startswith('**解析') or stripped.startswith('## 解析'):
            section = 'analysis'
            continue

        # 跳过 "正确答案是：" 行
        if re.match(r'^正确答案是[：:]', stripped):
            continue

        # 跳过 "【文段出处】" 行
        if stripped.startswith('【文段出处】'):
            continue

        if section == 'stem':
            # 检测选项开始：行首是 A. / A、/ A．
            if re.match(r'^[A-D][\.\．、\s]', stripped):
                section = 'options'
                option_lines.append(stripped)
            else:
                if stripped:
                    stem_lines.append(stripped)
        elif section == 'options':
            # 检测是否还是选项行，或者回到了题干
            if re.match(r'^[A-D][\.\．、\s]', stripped):
                option_lines.append(stripped)
            elif stripped.startswith('**解析') or stripped.startswith('## 解析'):
                section = 'analysis'
                continue
            elif not stripped:
                # 空行，可能是选项间的分隔
                pass
            else:
                # 非选项行，可能是题干的延续（如条件列表）
                # 检查是否包含选项标记
                if re.search(r'[A-D][\.\．、]', stripped):
                    option_lines.append(stripped)
                else:
                    # 可能是题干的一部分（条件、已知等）
                    stem_lines.append(stripped)
                    section = 'stem'
        elif section == 'analysis':
            analysis_lines.append(stripped)

    stem = '\n'.join(stem_lines).strip()
    analysis = '\n'.join(analysis_lines).strip()

    # 解析选项
    options = parse_options_from_lines(option_lines)

    # 清理分析：去掉末尾的 "故正确答案为 X"
    analysis = re.sub(r'\s*故正确答案为?\s*[A-D][。.]?\s*$', '', analysis).strip()
    # 去掉 "本题为选非题，故正确答案为X" 的变体
    analysis = re.sub(r'\s*本题为选非题[，,]\s*故正确答案为?\s*[A-D][。.]?\s*$', '', analysis).strip()
    # 去掉文段出处
    analysis = re.sub(r'##?\s*【文段出处】.*$', '', analysis, flags=re.DOTALL).strip()
    # 去掉块引用标记 "暂无答案和解析"
    analysis = re.sub(r'^>?\s*暂无答案和解析\s*$', '', analysis, flags=re.MULTILINE).strip()
    # 去掉单独的 "正确答案是：X" 行（没有实际解析内容的情况）
    analysis_cleaned = re.sub(r'^正确答案是[：:]\s*[A-D]\s*$', '', analysis, flags=re.MULTILINE).strip()
    if analysis_cleaned:
        analysis = analysis_cleaned
    # 去掉单独的 "解析" 行（无实际内容）
    analysis = re.sub(r'^解析\s*$', '', analysis, flags=re.MULTILINE).strip()

    # 标记 "暂无答案和解析" 的题目
    has_analysis = True
    if not analysis or '暂无' in analysis:
        has_analysis = False
        analysis = ''

    if not stem:
        return None

    result = {
        'no': q_no,
        'stem': stem,
        'options': options,
        'answer': answer,
        'analysis': analysis,
    }

    if not has_analysis:
        result['has_analysis'] = False

    if subtype_tag:
        result['tags'] = [subtype_tag]
    else:
        result['tags'] = []

    return result


def parse_file(filepath, category, type_name):
    """解析单个 md 文件，返回题目列表"""
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    filename = os.path.basename(filepath)
    subtype_tag = extract_subtype_from_filename(filename)
    group_no = extract_group_number(filename)
    prefix = ID_PREFIX.get(type_name, 'unknown')

    # 子类型短码
    subtype_code = SUBTYPE_CODE.get(subtype_tag, 'qt') if subtype_tag else 'qt'

    # 按 --- 分割题目块
    blocks = re.split(r'\n---\s*\n', content)

    questions = []
    for block in blocks:
        block = block.strip()
        if not block or not re.search(r'##\s*\d+\.\s*【答案】', block):
            continue

        q = parse_question_block(block, subtype_tag)
        if q:
            # ID格式: {prefix}_{subtype}_{group}_{no}
            q_id = f"{prefix}_{subtype_code}_{group_no:02d}_{q['no']:02d}"
            q['id'] = q_id
            q['group'] = group_no
            questions.append(q)

    return questions


def scan_directory(dir_path, category, type_name):
    """扫描目录下所有 md 文件"""
    all_questions = []
    files = sorted([f for f in os.listdir(dir_path) if f.endswith('.md')])
    for fname in files:
        fpath = os.path.join(dir_path, fname)
        qs = parse_file(fpath, category, type_name)
        all_questions.extend(qs)
    return all_questions, len(files)


def build_keywords_from_question(q):
    """从题目内容中提取关键词"""
    keywords = []
    stem = q.get('stem', '')
    analysis = q.get('analysis', '')

    # 从 tags 中提取
    keywords.extend(q.get('tags', []))

    # 从题干和解析中提取特征短语
    keyword_patterns = [
        '削弱', '加强', '前提', '假设', '质疑', '反驳', '支持',
        '翻译', '推理', '推出', '结论', '真假', '排列', '组合',
        '必要条件', '充分条件', '否前', '肯后', '逆否',
        '成语', '实词', '虚词', '搭配', '并列', '转折',
        '主旨', '意图', '细节', '态度', '标题',
        '种属', '并列关系', '对应关系', '组成关系',
        '论点', '论据', '拆桥', '搭桥',
    ]

    combined = stem + analysis
    for kw in keyword_patterns:
        if kw in combined and kw not in keywords:
            keywords.append(kw)

    return keywords


def main():
    pilot = '--pilot' in sys.argv

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    categories = {
        '言语': ['逻辑填空', '语句表达', '阅读理解'],
        '判断': ['类比推理', '定义判断', '逻辑判断'],
    }

    total_questions = 0
    total_files = 0
    index_data = {
        'version': '1.0',
        'total': 0,
        'categories': {},
    }

    for category, types in categories.items():
        cat_dir = os.path.join(QA_ROOT, category)
        if not os.path.isdir(cat_dir):
            continue

        index_data['categories'][category] = {}
        cat_output_dir = os.path.join(OUTPUT_DIR, category)
        os.makedirs(cat_output_dir, exist_ok=True)

        for type_name in types:
            if pilot and type_name != '逻辑判断':
                continue

            type_dir = os.path.join(cat_dir, type_name)
            if not os.path.isdir(type_dir):
                continue

            questions, file_count = scan_directory(type_dir, category, type_name)

            # 为每个题目生成关键词
            for q in questions:
                q['keywords'] = build_keywords_from_question(q)

            # 输出 JSON
            output = {
                'type': type_name,
                'category': category,
                'count': len(questions),
                'questions': questions,
            }

            out_path = os.path.join(cat_output_dir, f'{type_name}.json')
            with open(out_path, 'w', encoding='utf-8') as f:
                json.dump(output, f, ensure_ascii=False, indent=2)

            index_data['categories'][category][type_name] = {
                'count': len(questions),
                'file': f'{category}/{type_name}.json',
            }

            total_questions += len(questions)
            total_files += file_count
            print(f"  {category}/{type_name}: {file_count} files -> {len(questions)} questions")

    index_data['total'] = total_questions

    # 输出 index.json
    index_path = os.path.join(OUTPUT_DIR, 'index.json')
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(index_data, f, ensure_ascii=False, indent=2)

    print(f"\n总计: {total_files} files -> {total_questions} questions")
    print(f"输出目录: {OUTPUT_DIR}")


if __name__ == '__main__':
    main()
