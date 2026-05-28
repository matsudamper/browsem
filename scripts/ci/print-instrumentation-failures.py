#!/usr/bin/env python3
"""GMD インストルメンテーションテストの失敗を CI コンソールへ出力する。

Android Gradle Managed Device の結果ディレクトリから JUnit XML を読み取り、
失敗したテストのクラス名・メソッド名・メッセージ・スタックトレースを抽出して表示する。
アーティファクトをダウンロードしなくても CI ログ上で失敗テストが特定でき、
flaky テストの原因調査を容易にするのが目的。

引数で結果ディレクトリを指定できる。省略時は GMD (pixel6Api34) の既定パスを使う。
レポータとして動作するため、常に終了コード 0 で終わる（ビルドの成否は呼び出し側が制御する）。
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

# GMD (pixel6Api34) の JUnit 結果が出力される既定ディレクトリ
DEFAULT_RESULTS_DIR = "app/build/outputs/androidTest-results/managedDevice/debug/pixel6Api34"
# スタックトレース／logcat が肥大化した場合の出力上限
STACK_TRACE_MAX_LINES = 60
LOGCAT_TAIL_LINES = 80
# 注釈（check-run annotation）へ埋め込むスタックトレースの先頭行数。
# 注釈は GitHub API から取得できるため、ジョブログをダウンロードできない
# 環境でも失敗原因（例外種別・メッセージ・先頭フレーム）を確認できるようにする。
ANNOTATION_TRACE_HEAD_LINES = 20


def gha_escape(text):
    """GitHub Actions ワークフローコマンドのメッセージ用にエスケープする。

    改行を %0A へ変換することで、単一の ::warning:: コマンドでも複数行の
    注釈メッセージを生成できる（API 取得時に改行として復元される）。
    """
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def find_result_xml(results_dir):
    """結果ディレクトリ配下の JUnit XML を再帰的に集める。"""
    return sorted(glob.glob(os.path.join(results_dir, "**", "*.xml"), recursive=True))


def collect_failures(xml_path):
    """1 つの JUnit XML から failure / error のテストケースを抽出する。"""
    failures = []
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError as e:
        print(f"[XML パースエラー] {xml_path}: {e}")
        return failures
    for testcase in root.iter("testcase"):
        problems = testcase.findall("failure") + testcase.findall("error")
        if not problems:
            continue
        classname = testcase.get("classname") or ""
        method = testcase.get("name") or ""
        for problem in problems:
            failures.append(
                {
                    "test": f"{classname}.{method}".strip("."),
                    "method": method,
                    "kind": problem.tag,
                    "message": (problem.get("message") or "").strip(),
                    "trace": (problem.text or "").strip(),
                }
            )
    return failures


def find_logcat_for(method, results_dir):
    """失敗テストのメソッド名を含む logcat ファイルを探す（ベストエフォート）。"""
    if not method:
        return None
    for path in glob.glob(os.path.join(results_dir, "**", "*logcat*"), recursive=True):
        if method in os.path.basename(path):
            return path
    return None


def print_trace(trace):
    lines = trace.splitlines()
    for line in lines[:STACK_TRACE_MAX_LINES]:
        print("    " + line)
    remaining = len(lines) - STACK_TRACE_MAX_LINES
    if remaining > 0:
        print(f"    ... (残り {remaining} 行は省略)")


def print_logcat_tail(logcat_path):
    print(f"  --- logcat 末尾 ({os.path.basename(logcat_path)}) ---")
    try:
        with open(logcat_path, encoding="utf-8", errors="replace") as handle:
            tail = handle.read().splitlines()[-LOGCAT_TAIL_LINES:]
    except OSError as e:
        print(f"    (logcat 読み込み失敗: {e})")
        return
    for line in tail:
        print("    " + line)


def main():
    results_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RESULTS_DIR

    if not os.path.isdir(results_dir):
        print(f"::warning::テスト結果ディレクトリが見つかりません: {results_dir}")
        print(
            "エミュレータの起動失敗やテスト実行前のクラッシュの可能性があります。"
            "上の Gradle コンソール出力を確認してください。"
        )
        return

    all_failures = []
    for xml_path in find_result_xml(results_dir):
        all_failures.extend(collect_failures(xml_path))

    if not all_failures:
        print(f"::warning::{results_dir} に失敗テストの記録が見つかりませんでした。")
        print("テスト実行中のクラッシュやタイムアウトの可能性があります。")
        return

    print(f"::group::失敗したインストルメンテーションテスト ({len(all_failures)} 件)")
    for failure in all_failures:
        # 注釈にスタックトレース先頭を含め、ジョブログを取得できない環境でも
        # API 経由で失敗原因（例外・メッセージ・先頭フレーム）を特定できるようにする。
        annotation_lines = [f"FAILED({failure['kind']}): {failure['test']}"]
        if failure["message"]:
            annotation_lines.append(failure["message"])
        annotation_lines.extend(failure["trace"].splitlines()[:ANNOTATION_TRACE_HEAD_LINES])
        print("::warning::" + gha_escape("\n".join(annotation_lines)))
        if failure["trace"]:
            print_trace(failure["trace"])
        logcat_path = find_logcat_for(failure["method"], results_dir)
        if logcat_path:
            print_logcat_tail(logcat_path)
    print("::endgroup::")


if __name__ == "__main__":
    main()
